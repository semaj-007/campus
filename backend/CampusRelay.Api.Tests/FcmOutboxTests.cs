using CampusRelay.Api;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging.Abstractions;
using Microsoft.Extensions.Options;
using Xunit;

namespace CampusRelay.Api.Tests;

public sealed class FcmOutboxTests : IDisposable
{
    private readonly SqliteConnection connection = new("Data Source=:memory:");
    private readonly ServiceProvider provider;
    private readonly FakeClock clock = new();
    private readonly FakeSender sender = new();
    private readonly NotificationOptions settings = new() { EnableFcm = true };
    private readonly FcmOutboxDispatcher dispatcher;
    public FcmOutboxTests()
    {
        connection.Open();
        provider = new ServiceCollection().AddDbContext<CampusDb>(x => x.UseSqlite(connection)).BuildServiceProvider();
        using var scope = provider.CreateScope(); scope.ServiceProvider.GetRequiredService<CampusDb>().Database.EnsureCreated();
        dispatcher = new FcmOutboxDispatcher(provider.GetRequiredService<IServiceScopeFactory>(), sender, Options.Create(settings), clock, NullLogger<FcmOutboxDispatcher>.Instance);
    }
    private async Task<string> Seed(params string[] tokens)
    {
        using var scope = provider.CreateScope(); var db = scope.ServiceProvider.GetRequiredService<CampusDb>();
        var notification = new DeliveryEvent { UserId = "member", DeliveryId = "delivery", Status = "MATCHED", NextAttemptAt = clock.GetUtcNow().UtcDateTime };
        db.Events.Add(notification);
        foreach (var token in tokens) db.Devices.Add(new DeviceRegistration { UserId = "member", Token = token });
        await db.SaveChangesAsync(); return notification.Id;
    }
    private async Task<DeliveryEvent> Event(string id)
    {
        using var scope = provider.CreateScope(); return await scope.ServiceProvider.GetRequiredService<CampusDb>().Events.AsNoTracking().SingleAsync(x => x.Id == id);
    }
    [Fact] public async Task DisabledDispatcherDoesNotClaimOrSend()
    {
        var id = await Seed("device"); settings.EnableFcm = false;
        Assert.Equal(0, await dispatcher.ProcessBatchAsync()); Assert.Equal(0, sender.Count("device"));
        var e = await Event(id); Assert.Equal("PENDING", e.DispatchStatus); Assert.Equal(0, e.AttemptCount);
    }
    [Fact] public async Task SuccessfulSendMarksSentAndIsNotRepeated()
    {
        var id = await Seed("device"); await dispatcher.ProcessBatchAsync(); await dispatcher.ProcessBatchAsync();
        var e = await Event(id); Assert.Equal("SENT", e.DispatchStatus); Assert.Equal(1, e.AttemptCount);
        Assert.NotNull(e.SentAt); Assert.Null(e.LeaseUntil); Assert.Equal(1, sender.Count("device"));
        Assert.Equal(id, sender.LastEvent!.Id); Assert.Equal("delivery", sender.LastEvent.DeliveryId); Assert.Equal("MATCHED", sender.LastEvent.Status);
    }
    [Fact] public async Task FailureRemainsPendingWithBackoffAndSucceedsOnRetry()
    {
        var id = await Seed("device"); sender.Send = (_, n) => n == 1 ? throw new HttpRequestException("provider unavailable") : PushSendResult.Sent;
        await dispatcher.ProcessBatchAsync(); var failed = await Event(id);
        Assert.Equal("PENDING", failed.DispatchStatus); Assert.Equal(1, failed.AttemptCount); Assert.Null(failed.SentAt); Assert.NotNull(failed.LastError);
        Assert.True(failed.NextAttemptAt > clock.GetUtcNow().UtcDateTime);
        Assert.Equal(0, await dispatcher.ProcessBatchAsync());
        clock.Advance(TimeSpan.FromMinutes(1)); await dispatcher.ProcessBatchAsync();
        var success = await Event(id); Assert.Equal("SENT", success.DispatchStatus); Assert.Equal(2, success.AttemptCount); Assert.Equal(2, sender.Count("device"));
    }
    [Fact] public async Task PartialFailureDoesNotResendAlreadySuccessfulDevices()
    {
        var id = await Seed("first", "second"); sender.Send = (token, n) => token == "second" && n == 1 ? throw new HttpRequestException() : PushSendResult.Sent;
        await dispatcher.ProcessBatchAsync(); Assert.Equal("PENDING", (await Event(id)).DispatchStatus);
        clock.Advance(TimeSpan.FromMinutes(1)); await dispatcher.ProcessBatchAsync();
        Assert.Equal("SENT", (await Event(id)).DispatchStatus); Assert.Equal(1, sender.Count("first")); Assert.Equal(2, sender.Count("second"));
    }
    [Fact] public async Task MissingDevicesStayPendingWithoutFalseSuccess()
    {
        var id = await Seed(); await dispatcher.ProcessBatchAsync(); var e = await Event(id);
        Assert.Equal("PENDING", e.DispatchStatus); Assert.Equal(0, e.AttemptCount); Assert.Null(e.SentAt); Assert.True(e.NextAttemptAt > clock.GetUtcNow().UtcDateTime);
    }
    [Fact] public async Task InvalidDeviceIsRemovedAndEventIsUndeliverable()
    {
        var id = await Seed("expired"); sender.Send = (_, _) => PushSendResult.Unregistered;
        await dispatcher.ProcessBatchAsync(); Assert.Equal("UNDELIVERABLE", (await Event(id)).DispatchStatus);
        using var scope = provider.CreateScope(); Assert.Empty(await scope.ServiceProvider.GetRequiredService<CampusDb>().Devices.ToListAsync());
    }
    [Fact] public async Task RetryLimitMarksFailureWithoutClaimingDeliveryWasSent()
    {
        var id = await Seed("device"); settings.MaxAttempts = 1; sender.Send = (_, _) => throw new HttpRequestException();
        await dispatcher.ProcessBatchAsync(); var e = await Event(id); Assert.Equal("FAILED", e.DispatchStatus); Assert.Null(e.SentAt);
        clock.Advance(TimeSpan.FromHours(1)); Assert.Equal(0, await dispatcher.ProcessBatchAsync());
    }
    [Fact] public async Task ActiveLeasePreventsDuplicateClaimAndExpiredLeaseRecovers()
    {
        var id = await Seed("device");
        using (var scope = provider.CreateScope())
        {
            var db = scope.ServiceProvider.GetRequiredService<CampusDb>(); var e = await db.Events.FindAsync(id);
            e!.DispatchStatus = "PROCESSING"; e.ClaimToken = "another-worker"; e.LeaseUntil = clock.GetUtcNow().UtcDateTime.AddMinutes(2); await db.SaveChangesAsync();
        }
        Assert.Equal(0, await dispatcher.ProcessBatchAsync()); Assert.Equal(0, sender.Count("device"));
        clock.Advance(TimeSpan.FromMinutes(3)); Assert.Equal(1, await dispatcher.ProcessBatchAsync()); Assert.Equal("SENT", (await Event(id)).DispatchStatus);
    }
    public void Dispose() { provider.Dispose(); connection.Dispose(); }
    private sealed class FakeClock : TimeProvider
    {
        private DateTimeOffset now = DateTimeOffset.UtcNow;
        public override DateTimeOffset GetUtcNow() => now;
        public void Advance(TimeSpan duration) => now += duration;
    }
    private sealed class FakeSender : ICampusPushSender
    {
        private readonly Dictionary<string, int> calls = [];
        public Func<string, int, PushSendResult> Send { get; set; } = (_, _) => PushSendResult.Sent;
        public DeliveryEvent? LastEvent { get; private set; }
        public int Count(string token) => calls.GetValueOrDefault(token);
        public Task<PushSendResult> SendAsync(string deviceToken, DeliveryEvent notification, CancellationToken cancellationToken)
        {
            calls[deviceToken] = Count(deviceToken) + 1; LastEvent = notification;
            return Task.FromResult(Send(deviceToken, calls[deviceToken]));
        }
    }
}
