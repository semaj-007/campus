using System.Net;
using System.Security.Claims;
using CampusRelay.Api;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Microsoft.AspNetCore.Http;
using Xunit;

namespace CampusRelay.Api.Tests;

public sealed class CampusTests : IDisposable
{
    private readonly SqliteConnection connection = new("Data Source=:memory:");
    private readonly CampusDb db;
    private readonly CampusService service;
    private readonly ClaimsPrincipal sender = Member("sender");
    private readonly ClaimsPrincipal courier = Member("courier");
    public CampusTests()
    {
        connection.Open(); db = new CampusDb(new DbContextOptionsBuilder<CampusDb>().UseSqlite(connection).Options);
        db.Database.EnsureCreated(); service = new CampusService(db);
    }
    private static ClaimsPrincipal Member(string name) => new(new ClaimsIdentity(new[] { new Claim("sub", name), new Claim("email", name + "@campus.ac.za"), new Claim("name", name) }, "test"));
    private static DeliveryInput Delivery() => new("Textbook", "Engineering", "Library", -25.755, 28.231, -25.756, 28.234, .5, 2500, DateTime.UtcNow.AddMinutes(10), DateTime.UtcNow.AddHours(1));
    private static RouteInput Route(double capacity = 5) => new("Engineering", "Library", -25.755, 28.231, -25.756, 28.234, capacity, DateTime.UtcNow, DateTime.UtcNow.AddHours(2));
    private async Task<Delivery> MatchedDelivery()
    {
        var d = await service.CreateDelivery(sender, Delivery()); var o = await service.CreateOffer(courier, Route());
        await service.BookDelivery(sender, d.Id, o.Id); return d;
    }
    [Fact] public async Task MissingSubjectCannotCreateProfile() => await Assert.ThrowsAsync<CampusException>(() => service.Profile(new ClaimsPrincipal()));
    [Fact] public async Task ProfileIsCreatedOnceFromSsoClaims()
    {
        var a = await service.Profile(sender); var b = await service.Profile(sender);
        Assert.Equal(a.Id, b.Id); Assert.Equal("sender@campus.ac.za", a.Email); Assert.Single(await db.Users.ToListAsync());
    }
    [Fact] public async Task NewAccountRequiresProofAndRemainsPendingReview()
    {
        var identity = new ClaimsPrincipal(new ClaimsIdentity(new[] { new Claim("sub", "new-member"), new Claim("email", "new@gmail.com"), new Claim("name", "New Member") }, "Bearer"));
        var blocked = await Assert.ThrowsAsync<CampusException>(() => service.Profile(identity)); Assert.Equal(403, blocked.Status);
        var file = new FormFile(new MemoryStream("registration proof"u8.ToArray()), 0, 18, "proof", "proof.pdf") { Headers = new HeaderDictionary(), ContentType = "application/pdf" };
        await service.SubmitRegistration(identity, "New Member", "STU-123", file);
        var status = await service.AccountStatus(identity); Assert.Contains("pending", System.Text.Json.JsonSerializer.Serialize(status));
        Assert.Single(await db.RegistrationProofs.ToListAsync());
        await Assert.ThrowsAsync<CampusException>(() => service.Profile(identity));
    }
    [Fact] public async Task InvalidDeliveryWeightRejected()
    {
        var ex = await Assert.ThrowsAsync<CampusException>(() => service.CreateDelivery(sender, Delivery() with { WeightKg = -1 })); Assert.Equal(400, ex.Status); Assert.Empty(await db.Deliveries.ToListAsync());
    }
    [Fact] public async Task MatchingRequiresCapacityTimeAndRouteProximity()
    {
        var d = await service.CreateDelivery(sender, Delivery()); var good = await service.CreateOffer(courier, Route());
        await service.CreateOffer(courier, Route(.1));
        await service.CreateOffer(courier, Route() with { StartLat = -26.2, EndLat = -26.3 });
        await service.CreateOffer(courier, Route() with { WindowStart = DateTime.UtcNow.AddDays(1), WindowEnd = DateTime.UtcNow.AddDays(2) });
        var matches = await service.Matches(sender, d.Id); Assert.Single(matches); Assert.Equal(good.Id, matches[0].Id);
    }
    [Fact] public async Task HandoffRequiresAssignedCourierAndCorrectCode()
    {
        var d = await MatchedDelivery(); await service.Pickup(courier, d.Id);
        var unauthorized = await Assert.ThrowsAsync<CampusException>(() => service.CompleteDelivery(sender, d.Id, d.VerificationCode)); Assert.Equal(403, unauthorized.Status);
        await Assert.ThrowsAsync<CampusException>(() => service.CompleteDelivery(courier, d.Id, "invalid")); Assert.Equal("IN_PROGRESS", d.Status);
        await service.CompleteDelivery(courier, d.Id, d.VerificationCode); Assert.Equal("FULFILLED", d.Status); Assert.Equal(2, await db.EcoActions.CountAsync());
    }
    [Fact] public async Task OfflineRetriesDoNotDoubleCreditEco()
    {
        var d = await MatchedDelivery(); await service.Pickup(courier, d.Id);
        var input = new OfflineInput([new OfflineItem("local-1", d.Id, d.VerificationCode, DateTime.UtcNow)]);
        await service.Sync(courier, input); var score = (await service.Profile(courier)).EcoScoreKg;
        await service.Sync(courier, input); Assert.Equal(score, (await service.Profile(courier)).EcoScoreKg); Assert.Single(await db.OfflineReceipts.ToListAsync());
    }
    [Fact] public async Task RideBookingDoesNotOverbookOrAllowOwnRide()
    {
        var r = await service.CreateRide(courier, new("Hatfield", "Menlyn", DateTime.UtcNow.AddHours(1), 1, "CAR", 1500, 8));
        await Assert.ThrowsAsync<CampusException>(() => service.BookRide(courier, r.Id)); await service.BookRide(sender, r.Id);
        var error = await Assert.ThrowsAsync<CampusException>(() => service.BookRide(Member("third"), r.Id)); Assert.Equal(409, error.Status); Assert.Equal(0, r.AvailableSeats);
    }
    [Fact] public async Task ReservedListingCannotBePurchasedTwice()
    {
        var l = await service.CreateListing(courier, new("Algorithms", "Used textbook", "BOOKS", "GOOD", 15000));
        await service.ReserveListing(sender, l.Id); var e = await Assert.ThrowsAsync<CampusException>(() => service.ReserveListing(Member("third"), l.Id)); Assert.Equal(409, e.Status);
    }
    [Fact] public async Task PrivateMessagesRequireTransactionMembership()
    {
        var d = await MatchedDelivery(); await service.SendMessage(sender, d.TradeId!, new("Meet by the entrance"));
        var e = await Assert.ThrowsAsync<CampusException>(() => service.Messages(Member("third"), d.TradeId!)); Assert.Equal(403, e.Status); Assert.Single(await service.Messages(courier, d.TradeId!));
    }
    [Fact] public async Task ReviewRequiresCompletionAndIsUnique()
    {
        var d = await MatchedDelivery(); await Assert.ThrowsAsync<CampusException>(() => service.Review(sender, d.TradeId!, new(5, "Great")));
        await service.Pickup(courier, d.Id); await service.CompleteDelivery(courier, d.Id, d.VerificationCode); await service.Review(sender, d.TradeId!, new(5, "Great"));
        await Assert.ThrowsAsync<CampusException>(() => service.Review(sender, d.TradeId!, new(5, "Again"))); Assert.Equal(1, (await service.Profile(courier)).RatingCount);
    }
    public void Dispose() { db.Dispose(); connection.Dispose(); }
}
public class ApiSecurityTests
{
    [Fact] public async Task HealthWorksButProtectedApiRejectsAnonymousClients()
    {
        await using var factory = new WebApplicationFactory<Program>().WithWebHostBuilder(x => x.UseEnvironment("Testing"));
        var client = factory.CreateClient(); Assert.Equal(HttpStatusCode.OK, (await client.GetAsync("/health")).StatusCode);
        Assert.Equal(HttpStatusCode.Unauthorized, (await client.GetAsync("/api/v1/profile")).StatusCode);
        Assert.Equal(HttpStatusCode.Unauthorized, (await client.PostAsync("/api/v1/deliveries", null)).StatusCode);
    }
}

public class TokenPolicyTests
{
    private const string ProjectId = "campusrelay-tests";
    private static readonly Microsoft.IdentityModel.Tokens.SymmetricSecurityKey Key = new(System.Text.Encoding.UTF8.GetBytes("test-signing-key-only-not-a-production-secret-1234567890"));
    private static string Token(string provider = "google.com", string audience = ProjectId)
    {
        var token = new System.IdentityModel.Tokens.Jwt.JwtSecurityToken(
            issuer: $"https://securetoken.google.com/{ProjectId}", audience: audience,
            claims: [new Claim("sub", "token-member"), new Claim("email", "member@gmail.com"), new Claim("email_verified", "true"), new Claim("firebase", $"{{\"sign_in_provider\":\"{provider}\"}}")],
            expires: DateTime.UtcNow.AddMinutes(5), signingCredentials: new(Key, Microsoft.IdentityModel.Tokens.SecurityAlgorithms.HmacSha256));
        return new System.IdentityModel.Tokens.Jwt.JwtSecurityTokenHandler().WriteToken(token);
    }
    [Fact] public async Task InvalidAudienceIsRejectedAndNonGoogleProviderIsForbidden()
    {
        await using var factory = new WebApplicationFactory<Program>().WithWebHostBuilder(web =>
        {
            web.UseEnvironment("Testing");
            web.UseSetting("Firebase:ProjectId", ProjectId);
            web.ConfigureServices(services =>
            {
                Microsoft.Extensions.DependencyInjection.OptionsServiceCollectionExtensions.PostConfigure<Microsoft.AspNetCore.Authentication.JwtBearer.JwtBearerOptions>(services,
                    Microsoft.AspNetCore.Authentication.JwtBearer.JwtBearerDefaults.AuthenticationScheme, options =>
                    {
                        options.ConfigurationManager = null;
                        options.Configuration = new Microsoft.IdentityModel.Protocols.OpenIdConnect.OpenIdConnectConfiguration();
                        options.TokenValidationParameters.IssuerSigningKey = Key;
                    });
            });
        });
        var client = factory.CreateClient();
        client.DefaultRequestHeaders.Authorization = new("Bearer", Token(audience: "wrong-audience"));
        Assert.Equal(HttpStatusCode.Unauthorized, (await client.GetAsync("/api/v1/profile")).StatusCode);
        client.DefaultRequestHeaders.Authorization = new("Bearer", Token("password"));
        Assert.Equal(HttpStatusCode.Forbidden, (await client.GetAsync("/api/v1/profile")).StatusCode);
    }
}
