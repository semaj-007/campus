using Microsoft.EntityFrameworkCore;

namespace CampusRelay.Api;

public sealed class CampusDb(DbContextOptions<CampusDb> options) : DbContext(options)
{
    public DbSet<CampusUser> Users => Set<CampusUser>();
    public DbSet<Delivery> Deliveries => Set<Delivery>();
    public DbSet<RouteOffer> Offers => Set<RouteOffer>();
    public DbSet<Ride> Rides => Set<Ride>();
    public DbSet<Listing> Listings => Set<Listing>();
    public DbSet<Trade> Trades => Set<Trade>();
    public DbSet<ChatMessage> Messages => Set<ChatMessage>();
    public DbSet<EcoAction> EcoActions => Set<EcoAction>();
    public DbSet<OfflineReceipt> OfflineReceipts => Set<OfflineReceipt>();
    public DbSet<Review> Reviews => Set<Review>();
    public DbSet<DeliveryEvent> Events => Set<DeliveryEvent>();
    public DbSet<DeviceRegistration> Devices => Set<DeviceRegistration>();
    public DbSet<PushReceipt> PushReceipts => Set<PushReceipt>();
    public DbSet<RegistrationProof> RegistrationProofs => Set<RegistrationProof>();
    protected override void OnModelCreating(ModelBuilder model)
    {
        model.Entity<CampusUser>().HasIndex(x => x.Subject).IsUnique();
        model.Entity<DeviceRegistration>().HasIndex(x => x.Token).IsUnique();
        model.Entity<PushReceipt>().HasKey(x => new { x.EventId, x.DeviceId });
        model.Entity<DeliveryEvent>().HasIndex(x => new { x.DispatchStatus, x.NextAttemptAt });
        model.Entity<CampusUser>().HasIndex(x => x.Email).IsUnique();
        model.Entity<RegistrationProof>().HasIndex(x => x.UserId).IsUnique();
        model.Entity<CampusUser>().Property(x => x.Revision).IsConcurrencyToken();
        model.Entity<RouteOffer>().Property(x => x.Revision).IsConcurrencyToken();
        model.Entity<Delivery>().Property(x => x.Revision).IsConcurrencyToken();
        model.Entity<Ride>().Property(x => x.Revision).IsConcurrencyToken();
        model.Entity<Listing>().Property(x => x.Revision).IsConcurrencyToken();
        model.Entity<OfflineReceipt>().HasKey(x => new { x.UserId, x.LocalTransactionId });
        model.Entity<Review>().HasIndex(x => new { x.TradeId, x.ReviewerId }).IsUnique();
        model.Entity<ChatMessage>().HasIndex(x => new { x.TradeId, x.SentAt });
        model.Entity<EcoAction>().HasIndex(x => new { x.UserId, x.TradeId }).IsUnique();
    }
}

public sealed class CampusUser
{
    public string Id { get; set; } = Guid.NewGuid().ToString();
    public string Subject { get; set; } = "";
    public string Email { get; set; } = "";
    public string FullName { get; set; } = "";
    public string Bio { get; set; } = "";
    public string CampusLocation { get; set; } = "";
    public string Language { get; set; } = "en";
    public double EcoScoreKg { get; set; }
    public int Revision { get; set; }
    public int RatingSum { get; set; }
    public int RatingCount { get; set; }
    public DateTimeOffset CreatedAt { get; set; } = DateTimeOffset.UtcNow;
    public string RegistrationStatus { get; set; } = "UNVERIFIED";
}

public sealed class RegistrationProof
{
    public string Id { get; set; } = Guid.NewGuid().ToString();
    public string UserId { get; set; } = "";
    public string StudentNumber { get; set; } = "";
    public string FileName { get; set; } = "";
    public string ContentType { get; set; } = "";
    public byte[] Content { get; set; } = [];
    public DateTimeOffset SubmittedAt { get; set; } = DateTimeOffset.UtcNow;
}

public sealed class Delivery
{
    public string Id { get; set; } = Guid.NewGuid().ToString();
    public string RequesterId { get; set; } = "";
    public string? CourierId { get; set; }
    public string? TradeId { get; set; }
    public string ItemDescription { get; set; } = "";
    public string PickupBuilding { get; set; } = "";
    public string DropoffBuilding { get; set; } = "";
    public double PickupLat { get; set; }
    public double PickupLng { get; set; }
    public double DropoffLat { get; set; }
    public double DropoffLng { get; set; }
    public double WeightKg { get; set; }
    public long RewardCents { get; set; }
    public DateTime WindowStart { get; set; }
    public DateTime WindowEnd { get; set; }
    public string Status { get; set; } = "ACTIVE";
    public string VerificationCode { get; set; } = Convert.ToHexString(System.Security.Cryptography.RandomNumberGenerator.GetBytes(16));
    public DateTimeOffset CreatedAt { get; set; } = DateTimeOffset.UtcNow;
    public int Revision { get; set; }
}

public sealed class RouteOffer
{
    public string Id { get; set; } = Guid.NewGuid().ToString();
    public string CourierId { get; set; } = "";
    public string StartBuilding { get; set; } = "";
    public string EndBuilding { get; set; } = "";
    public double StartLat { get; set; }
    public double StartLng { get; set; }
    public double EndLat { get; set; }
    public double EndLng { get; set; }
    public double CapacityKg { get; set; }
    public int Revision { get; set; }
    public DateTime WindowStart { get; set; }
    public DateTime WindowEnd { get; set; }
}

public sealed class Ride
{
    public string Id { get; set; } = Guid.NewGuid().ToString();
    public string DriverId { get; set; } = "";
    public string Origin { get; set; } = "";
    public string Destination { get; set; } = "";
    public DateTime DepartureTime { get; set; }
    public int AvailableSeats { get; set; }
    public string VehicleType { get; set; } = "CAR";
    public long PricePerSeatCents { get; set; }
    public double DistanceKm { get; set; }
    public string Status { get; set; } = "ACTIVE";
    public int Revision { get; set; }
}

public sealed class Listing
{
    public string Id { get; set; } = Guid.NewGuid().ToString();
    public string SellerId { get; set; } = "";
    public string Title { get; set; } = "";
    public string Description { get; set; } = "";
    public string Category { get; set; } = "BOOKS";
    public string Condition { get; set; } = "GOOD";
    public long PriceCents { get; set; }
    public string Status { get; set; } = "ACTIVE";
    public int Revision { get; set; }
}

public sealed class Trade
{
    public string Id { get; set; } = Guid.NewGuid().ToString();
    public string Type { get; set; } = "DELIVERY";
    public string RequesterId { get; set; } = "";
    public string ProviderId { get; set; } = "";
    public string ServiceRefId { get; set; } = "";
    public long AgreedPriceCents { get; set; }
    public string Status { get; set; } = "ACCEPTED";
    public string EscrowStatus { get; set; } = "NOT_FUNDED";
    public DateTime CreatedAt { get; set; } = DateTime.UtcNow;
}
public sealed class ChatMessage
{
    public string Id { get; set; } = Guid.NewGuid().ToString();
    public string TradeId { get; set; } = "";
    public string SenderId { get; set; } = "";
    public string Content { get; set; } = "";
    public DateTime SentAt { get; set; } = DateTime.UtcNow;
}
public sealed class EcoAction
{
    public string Id { get; set; } = Guid.NewGuid().ToString();
    public string UserId { get; set; } = "";
    public string TradeId { get; set; } = "";
    public string Description { get; set; } = "";
    public double SavedKg { get; set; }
    public DateTime CompletedAt { get; set; } = DateTime.UtcNow;
}
public sealed class OfflineReceipt
{
    public string UserId { get; set; } = "";
    public string LocalTransactionId { get; set; } = "";
    public string DeliveryId { get; set; } = "";
}
public sealed class Review
{
    public string Id { get; set; } = Guid.NewGuid().ToString();
    public string TradeId { get; set; } = "";
    public string ReviewerId { get; set; } = "";
    public string RevieweeId { get; set; } = "";
    public int Rating { get; set; }
    public string Comment { get; set; } = "";
}
// Durable outbox: a configured FCM dispatcher may deliver pending events.
public sealed class DeliveryEvent
{
    public string Id { get; set; } = Guid.NewGuid().ToString();
    public string UserId { get; set; } = "";
    public string DeliveryId { get; set; } = "";
    public string Status { get; set; } = "";
    public string DispatchStatus { get; set; } = "PENDING";
    public int AttemptCount { get; set; }
    public string? ClaimToken { get; set; }
    public DateTime? LeaseUntil { get; set; }
    public DateTime NextAttemptAt { get; set; } = DateTime.UtcNow;
    public string? LastError { get; set; }
    public DateTime? SentAt { get; set; }
    public DateTime CreatedAt { get; set; } = DateTime.UtcNow;
}

public record DeliveryInput(string ItemDescription, string PickupBuilding, string DropoffBuilding,
    double PickupLat, double PickupLng, double DropoffLat, double DropoffLng, double WeightKg,
    long RewardCents, DateTime WindowStart, DateTime WindowEnd);
public record RouteInput(string StartBuilding, string EndBuilding, double StartLat, double StartLng,
    double EndLat, double EndLng, double CapacityKg, DateTime WindowStart, DateTime WindowEnd);
public record RideInput(string Origin, string Destination, DateTime DepartureTime, int AvailableSeats,
    string VehicleType, long PricePerSeatCents, double DistanceKm);
public record ListingInput(string Title, string Description, string Category, string Condition, long PriceCents);
public record ProfileInput(string FullName, string Bio, string CampusLocation, string Language);
public record BookingInput(string OfferId);
public record HandoffInput(string ScannedQrHash);
public record OfflineInput(List<OfflineItem> OfflineTransactions);
public record OfflineItem(string LocalTransactionId, string DeliveryId, string ScannedQrHash, DateTime CompletedAt);
public record MessageInput(string Content);
public record ReviewInput(int Rating, string Comment);

public sealed class DeviceRegistration
{
    public string Id { get; set; } = Guid.NewGuid().ToString();
    public string UserId { get; set; } = "";
    public string Token { get; set; } = "";
    public string Platform { get; set; } = "android";
    public DateTime UpdatedAt { get; set; } = DateTime.UtcNow;
}
public record DeviceInput(string Token, string Platform);

// Records per-device successes so a failed second device does not resend to the first.
public sealed class PushReceipt
{
    public string EventId { get; set; } = "";
    public string DeviceId { get; set; } = "";
    public DateTime SentAt { get; set; } = DateTime.UtcNow;
}
