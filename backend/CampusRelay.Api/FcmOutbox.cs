using FirebaseAdmin;
using FirebaseAdmin.Messaging;
using Google.Apis.Auth.OAuth2;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;

namespace CampusRelay.Api;

public sealed class NotificationOptions
{
    public bool EnableFcm { get; set; }
    public string FirebaseProjectId { get; set; } = "";
    public int PollSeconds { get; set; } = 15;
    public int MaxAttempts { get; set; } = 8;
}
public enum PushSendResult { Sent, Unregistered }
public interface ICampusPushSender
{
    Task<PushSendResult> SendAsync(string deviceToken, DeliveryEvent notification, CancellationToken cancellationToken);
}

public sealed class FirebaseCampusPushSender(IOptions<NotificationOptions> options) : ICampusPushSender, IDisposable
{
    private FirebaseApp? app;
    private readonly SemaphoreSlim initialization = new(1, 1);
    private async Task<FirebaseMessaging> Messaging(CancellationToken ct)
    {
        await initialization.WaitAsync(ct);
        try
        {
            if (app == null)
            {
                // SDK credential loading honors workload identity / GOOGLE_APPLICATION_CREDENTIALS.
                // Credentials never enter application responses, logs, or Android configuration.
                var credential = await GoogleCredential.GetApplicationDefaultAsync(ct);
                app = FirebaseApp.Create(new AppOptions { Credential = credential, ProjectId = options.Value.FirebaseProjectId }, "CampusRelayOutbox");
            }
            return FirebaseMessaging.GetMessaging(app);
        }
        finally { initialization.Release(); }
    }
    public async Task<PushSendResult> SendAsync(string deviceToken, DeliveryEvent notification, CancellationToken cancellationToken)
    {
        var message = new Message
        {
            // The Android Firebase SDK in this app registers FCM tokens, not installation IDs.
            // Firebase Admin 3.7 retains the token transport for these existing clients.
#pragma warning disable CS0618
            Token = deviceToken,
#pragma warning restore CS0618
            Data = new Dictionary<string, string>
            {
                ["type"] = "delivery_status", ["deliveryId"] = notification.DeliveryId,
                ["status"] = notification.Status, ["notificationId"] = notification.Id,
                ["title"] = "CampusRelay delivery update", ["body"] = $"Delivery status: {notification.Status}"
            },
            // Data payload lets the Android service apply its own notification permission/settings.
            Android = new AndroidConfig { Priority = Priority.High, TimeToLive = TimeSpan.FromHours(1) }
        };
        try
        {
            await (await Messaging(cancellationToken)).SendAsync(message, cancellationToken);
            return PushSendResult.Sent;
        }
        catch (FirebaseMessagingException ex) when (ex.MessagingErrorCode == MessagingErrorCode.Unregistered)
        {
            return PushSendResult.Unregistered;
        }
    }
    public void Dispose() { app?.Delete(); initialization.Dispose(); }
}

public sealed class FcmOutboxDispatcher(IServiceScopeFactory scopeFactory, ICampusPushSender sender,
    IOptions<NotificationOptions> options, TimeProvider clock, ILogger<FcmOutboxDispatcher> logger)
{
    // Claims use conditional UPDATEs, so concurrent hosts do not process the same live lease.
    public async Task<int> ProcessBatchAsync(CancellationToken ct = default)
    {
        if (!options.Value.EnableFcm) return 0;
        using var scope = scopeFactory.CreateScope();
        var db = scope.ServiceProvider.GetRequiredService<CampusDb>();
        var now = clock.GetUtcNow().UtcDateTime;
        var ids = await Eligible(db, now).OrderBy(x => x.CreatedAt).Select(x => x.Id).Take(25).ToListAsync(ct);
        var processed = 0;
        foreach (var id in ids)
        {
            ct.ThrowIfCancellationRequested();
            var claim = Guid.NewGuid().ToString();
            var changed = await Eligible(db, now).Where(x => x.Id == id).ExecuteUpdateAsync(setters => setters
                .SetProperty(x => x.DispatchStatus, "PROCESSING").SetProperty(x => x.ClaimToken, claim)
                .SetProperty(x => x.LeaseUntil, now.AddMinutes(2)).SetProperty(x => x.AttemptCount, x => x.AttemptCount + 1), ct);
            if (changed == 0) continue;
            processed++;
            var item = await db.Events.AsNoTracking().SingleAsync(x => x.Id == id && x.ClaimToken == claim, ct);
            var devices = await db.Devices.AsNoTracking().Where(x => x.UserId == item.UserId && x.Platform == "android").ToListAsync(ct);
            if (devices.Count == 0)
            {
                await db.Events.Where(x => x.Id == id && x.ClaimToken == claim).ExecuteUpdateAsync(setters => setters
                    .SetProperty(x => x.DispatchStatus, "PENDING").SetProperty(x => x.ClaimToken, (string?)null)
                    .SetProperty(x => x.LeaseUntil, (DateTime?)null).SetProperty(x => x.NextAttemptAt, now.AddMinutes(5))
                    .SetProperty(x => x.AttemptCount, x => x.AttemptCount - 1).SetProperty(x => x.LastError, "No registered Android device."), ct);
                continue;
            }
            var failures = false; var sent = 0;
            foreach (var device in devices)
            {
                if (await db.PushReceipts.AnyAsync(x => x.EventId == id && x.DeviceId == device.Id, ct)) { sent++; continue; }
                // Renew the lease before each network call. An event with many devices remains exclusive.
                var owned = await db.Events.Where(x => x.Id == id && x.ClaimToken == claim).ExecuteUpdateAsync(setters => setters.SetProperty(x => x.LeaseUntil, clock.GetUtcNow().UtcDateTime.AddMinutes(2)), ct);
                if (owned == 0) break;
                try
                {
                    using var deadline = CancellationTokenSource.CreateLinkedTokenSource(ct);
                    deadline.CancelAfter(TimeSpan.FromSeconds(45));
                    var result = await sender.SendAsync(device.Token, item, deadline.Token);
                    if (result == PushSendResult.Unregistered)
                    {
                        await db.Devices.Where(x => x.Id == device.Id && x.Token == device.Token).ExecuteDeleteAsync(ct);
                        continue;
                    }
                    db.PushReceipts.Add(new PushReceipt { EventId = id, DeviceId = device.Id, SentAt = clock.GetUtcNow().UtcDateTime });
                    await db.SaveChangesAsync(ct); sent++;
                }
                catch (OperationCanceledException) when (ct.IsCancellationRequested) { throw; }
                catch (Exception)
                {
                    // Provider errors may contain a token or credential context; never log their text.
                    failures = true;
                    logger.LogWarning("FCM delivery failed for outbox event {EventId}; retry recorded.", id);
                }
            }
            var state = failures ? item.AttemptCount >= Math.Clamp(options.Value.MaxAttempts, 1, 20) ? "FAILED" : "PENDING"
                : sent > 0 ? "SENT" : "UNDELIVERABLE";
            var retry = now.AddSeconds(Math.Min(3600, 30 * Math.Pow(2, Math.Min(item.AttemptCount - 1, 7))));
            await db.Events.Where(x => x.Id == id && x.ClaimToken == claim).ExecuteUpdateAsync(setters => setters
                .SetProperty(x => x.DispatchStatus, state).SetProperty(x => x.ClaimToken, (string?)null)
                .SetProperty(x => x.LeaseUntil, (DateTime?)null).SetProperty(x => x.NextAttemptAt, retry)
                .SetProperty(x => x.LastError, failures ? "FCM send failed; retry or operator action required." : sent == 0 ? "All registered device tokens are invalid." : null)
                .SetProperty(x => x.SentAt, state == "SENT" ? clock.GetUtcNow().UtcDateTime : (DateTime?)null), ct);
        }
        return processed;
    }
    private static IQueryable<DeliveryEvent> Eligible(CampusDb db, DateTime now) => db.Events.Where(x =>
        (x.DispatchStatus == "PENDING" && x.NextAttemptAt <= now) ||
        (x.DispatchStatus == "PROCESSING" && x.LeaseUntil < now));
}

public sealed class FcmOutboxWorker(FcmOutboxDispatcher dispatcher, IOptions<NotificationOptions> options,
    ILogger<FcmOutboxWorker> logger) : BackgroundService
{
    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        while (!stoppingToken.IsCancellationRequested)
        {
            try { await dispatcher.ProcessBatchAsync(stoppingToken); }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested) { break; }
            catch (Exception) { logger.LogError("FCM outbox pass failed; pending or leased events remain recoverable."); }
            try { await Task.Delay(TimeSpan.FromSeconds(Math.Clamp(options.Value.PollSeconds, 5, 300)), stoppingToken); }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested) { break; }
        }
    }
}
