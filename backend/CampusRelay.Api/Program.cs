using System.Security.Claims;
using CampusRelay.Api;
using Microsoft.AspNetCore.Authentication.JwtBearer;
using Microsoft.EntityFrameworkCore;
using Microsoft.IdentityModel.Tokens;

var builder = WebApplication.CreateBuilder(args);
var tenant = builder.Configuration["Identity:TenantId"] ?? "";
var audience = builder.Configuration["Identity:Audience"] ?? "";
var domain = builder.Configuration["Identity:AllowedEmailDomain"] ?? "";
if (!builder.Environment.IsDevelopment() && !builder.Environment.IsEnvironment("Testing") &&
    (!Guid.TryParse(tenant, out _) || string.IsNullOrWhiteSpace(audience) || string.IsNullOrWhiteSpace(domain)))
    throw new InvalidOperationException("Configure Identity:TenantId, Audience and AllowedEmailDomain before production startup.");
builder.Services.AddAuthentication(JwtBearerDefaults.AuthenticationScheme).AddJwtBearer(options =>
{
    if (Guid.TryParse(tenant, out _)) options.Authority = $"https://login.microsoftonline.com/{tenant}/v2.0";
    options.Audience = audience;
    options.MapInboundClaims = false;
    options.RequireHttpsMetadata = true;
    options.TokenValidationParameters = new TokenValidationParameters
    {
        ValidateIssuer = true, ValidateAudience = true, ValidateIssuerSigningKey = true,
        ValidateLifetime = true, ClockSkew = TimeSpan.FromMinutes(1),
        ValidIssuer = $"https://login.microsoftonline.com/{tenant}/v2.0", ValidAudience = audience
    };
});
builder.Services.AddAuthorization(options => options.AddPolicy("Campus", policy =>
    policy.RequireAuthenticatedUser().RequireAssertion(context =>
    {
        var email = context.User.FindFirstValue("email") ?? context.User.FindFirstValue("preferred_username");
        return !string.IsNullOrWhiteSpace(domain) && email != null &&
               email.EndsWith("@" + domain, StringComparison.OrdinalIgnoreCase) &&
               context.User.FindFirstValue("tid") == tenant && context.User.HasClaim(c => c.Type == "sub");
    })));
builder.Services.AddDbContext<CampusDb>(options =>
{
    var connection = builder.Configuration.GetConnectionString("Campus") ?? "Data Source=campusrelay.db";
    if (builder.Configuration["Database:Provider"] == "SqlServer") options.UseSqlServer(connection);
    else options.UseSqlite(connection);
});
builder.Services.AddScoped<CampusService>();
builder.Services.Configure<NotificationOptions>(builder.Configuration.GetSection("Notifications"));
builder.Services.AddSingleton(TimeProvider.System);
builder.Services.AddSingleton<ICampusPushSender, FirebaseCampusPushSender>();
builder.Services.AddSingleton<FcmOutboxDispatcher>();
if (builder.Configuration.GetValue<bool>("Notifications:EnableFcm"))
{
    if (string.IsNullOrWhiteSpace(builder.Configuration["Notifications:FirebaseProjectId"]))
        throw new InvalidOperationException("Set Notifications:FirebaseProjectId before enabling FCM.");
    builder.Services.AddHostedService<FcmOutboxWorker>();
}
var app = builder.Build();
app.Use(async (context, next) =>
{
    try { await next(context); }
    catch (CampusException e) { context.Response.StatusCode = e.Status; await context.Response.WriteAsJsonAsync(new { error = e.Message }); }
    catch (DbUpdateConcurrencyException) { context.Response.StatusCode = 409; await context.Response.WriteAsJsonAsync(new { error = "This item changed. Refresh and try again." }); }
});
app.UseAuthentication();
app.UseAuthorization();
app.MapGet("/health", () => Results.Ok(new { status = "ok", service = "CampusRelay" }));
var api = app.MapGroup("/api/v1").RequireAuthorization("Campus");
api.MapGet("/profile", async (CampusService s, ClaimsPrincipal u) => Results.Ok(await s.Profile(u)));
api.MapPut("/profile", async (CampusService s, ClaimsPrincipal u, ProfileInput input) => Results.Ok(await s.UpdateProfile(u, input)));
api.MapPost("/profile/device-token", async (CampusService s, ClaimsPrincipal u, DeviceInput input) => Results.Ok(await s.RegisterDevice(u, input)));
api.MapGet("/eco", async (CampusService s, ClaimsPrincipal u) => Results.Ok(await s.Eco(u)));
api.MapGet("/deliveries", async (CampusService s, ClaimsPrincipal u) => Results.Ok(await s.Deliveries(u)));
api.MapPost("/deliveries", async (CampusService s, ClaimsPrincipal u, DeliveryInput input) => { var d = await s.CreateDelivery(u, input); return Results.Created($"/api/v1/deliveries/{d.Id}", s.DeliveryView(d)); });
api.MapGet("/deliveries/{id}", async (CampusService s, ClaimsPrincipal u, string id) => Results.Ok(await s.GetDelivery(u, id)));
api.MapGet("/deliveries/{id}/handoff-code", async (CampusService s, ClaimsPrincipal u, string id) => Results.Ok(await s.HandoffCode(u, id)));
api.MapPost("/delivery-offers", async (CampusService s, ClaimsPrincipal u, RouteInput input) => { var o = await s.CreateOffer(u, input); return Results.Created($"/api/v1/delivery-offers/{o.Id}", o); });
api.MapGet("/deliveries/{id}/matches", async (CampusService s, ClaimsPrincipal u, string id) => Results.Ok(await s.Matches(u, id)));
api.MapPost("/deliveries/{id}/book", async (CampusService s, ClaimsPrincipal u, string id, BookingInput input) => Results.Ok(await s.BookDelivery(u, id, input.OfferId)));
api.MapPost("/deliveries/{id}/pickup", async (CampusService s, ClaimsPrincipal u, string id) => Results.Ok(await s.Pickup(u, id)));
api.MapPost("/deliveries/{id}/handoff", async (CampusService s, ClaimsPrincipal u, string id, HandoffInput input) => Results.Ok(await s.CompleteDelivery(u, id, input.ScannedQrHash)));
api.MapPost("/deliveries/{id}/cancel", async (CampusService s, ClaimsPrincipal u, string id) => Results.Ok(await s.CancelDelivery(u, id)));
api.MapPost("/deliveries/sync-offline", async (CampusService s, ClaimsPrincipal u, OfflineInput input) => Results.Ok(await s.Sync(u, input)));
api.MapGet("/rides", async (CampusService s, ClaimsPrincipal u, string? origin, string? destination, DateTime? date) => Results.Ok(await s.SearchRides(u, origin, destination, date)));
api.MapPost("/rides", async (CampusService s, ClaimsPrincipal u, RideInput input) => { var ride = await s.CreateRide(u, input); return Results.Created($"/api/v1/rides/{ride.Id}", ride); });
api.MapPost("/rides/{id}/book", async (CampusService s, ClaimsPrincipal u, string id) => Results.Ok(await s.BookRide(u, id)));
api.MapGet("/marketplace", async (CampusService s, ClaimsPrincipal u, string? category) => Results.Ok(await s.Marketplace(u, category)));
api.MapPost("/marketplace", async (CampusService s, ClaimsPrincipal u, ListingInput input) => { var l = await s.CreateListing(u, input); return Results.Created($"/api/v1/marketplace/{l.Id}", l); });
api.MapPost("/marketplace/{id}/reserve", async (CampusService s, ClaimsPrincipal u, string id) => Results.Ok(await s.ReserveListing(u, id)));
api.MapGet("/transactions", async (CampusService s, ClaimsPrincipal u) => Results.Ok(await s.Transactions(u)));
api.MapPost("/transactions/{id}/complete", async (CampusService s, ClaimsPrincipal u, string id) => Results.Ok(await s.CompleteTrade(u, id)));
api.MapGet("/transactions/{id}/messages", async (CampusService s, ClaimsPrincipal u, string id) => Results.Ok(await s.Messages(u, id)));
api.MapPost("/transactions/{id}/messages", async (CampusService s, ClaimsPrincipal u, string id, MessageInput input) => Results.Ok(await s.SendMessage(u, id, input)));
api.MapPost("/transactions/{id}/review", async (CampusService s, ClaimsPrincipal u, string id, ReviewInput input) => Results.Ok(await s.Review(u, id, input)));
api.MapGet("/notifications", async (CampusService s, ClaimsPrincipal u) => Results.Ok(await s.Notifications(u)));
// For local development only. Production schema changes must be applied as reviewed EF migrations.
if (app.Environment.IsDevelopment())
{
    using var scope = app.Services.CreateScope();
    await scope.ServiceProvider.GetRequiredService<CampusDb>().Database.EnsureCreatedAsync();
}
app.Run();
public partial class Program { }
