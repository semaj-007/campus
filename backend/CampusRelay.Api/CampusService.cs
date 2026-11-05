using System.Security.Claims;
using System.Security.Cryptography;
using System.Text;
using Microsoft.EntityFrameworkCore;
namespace CampusRelay.Api;

public sealed class CampusException(string message, int status = 400) : Exception(message) { public int Status => status; }

public static class Rules
{
    public static void Require(bool condition, string message, int status = 400) { if (!condition) throw new CampusException(message, status); }
    public static string Text(string? text, string label, int max = 200)
    {
        var s = text?.Trim() ?? "";
        Require(s.Length > 0 && s.Length <= max, $"{label} must contain 1–{max} characters."); return s;
    }
    public static void Coordinates(double lat, double lng) => Require(double.IsFinite(lat) && double.IsFinite(lng) && lat >= -90 && lat <= 90 && lng >= -180 && lng <= 180, "Invalid coordinates.");
    public static void Window(DateTime start, DateTime end) => Require(start.Kind == DateTimeKind.Utc && end.Kind == DateTimeKind.Utc && end > start && end > DateTime.UtcNow && end - start <= TimeSpan.FromDays(7), "Use a valid UTC delivery window, at most seven days long.");
    public static double DistanceKm(double lat1, double lng1, double lat2, double lng2)
    {
        var a = Math.Sin((lat2 - lat1) * Math.PI / 360); var b = Math.Sin((lng2 - lng1) * Math.PI / 360);
        return 6371 * 2 * Math.Asin(Math.Min(1, Math.Sqrt(a * a + Math.Cos(lat1 * Math.PI / 180) * Math.Cos(lat2 * Math.PI / 180) * b * b)));
    }
    // Distance from a waypoint to the route segment using a local equirectangular projection.
    public static double RouteDistanceKm(double lat, double lng, RouteOffer o)
    {
        var cosine = Math.Cos(lat * Math.PI / 180); var x = (lng - o.StartLng) * 111.195 * cosine; var y = (lat - o.StartLat) * 111.195;
        var dx = (o.EndLng - o.StartLng) * 111.195 * cosine; var dy = (o.EndLat - o.StartLat) * 111.195;
        var length = dx * dx + dy * dy; var t = length == 0 ? 0 : Math.Clamp((x * dx + y * dy) / length, 0, 1);
        return Math.Sqrt(Math.Pow(x - t * dx, 2) + Math.Pow(y - t * dy, 2));
    }
    public static bool Matches(Delivery d, RouteOffer o) => d.WeightKg <= o.CapacityKg && d.WindowStart < o.WindowEnd && o.WindowStart < d.WindowEnd && RouteDistanceKm(d.PickupLat, d.PickupLng, o) <= 1 && RouteDistanceKm(d.DropoffLat, d.DropoffLng, o) <= 1;
}

public sealed class CampusService(CampusDb db)
{
    public async Task<CampusUser> Profile(ClaimsPrincipal principal)
    {
        var subject = principal.FindFirstValue("sub"); Rules.Require(!string.IsNullOrEmpty(subject), "Authentication required.", 401);
        var user = await db.Users.SingleOrDefaultAsync(x => x.Subject == subject);
        if (user != null) return user;
        user = new CampusUser { Subject = subject!, Email = principal.FindFirstValue("email") ?? "", FullName = principal.FindFirstValue("name") ?? "Campus member" };
        db.Users.Add(user); await db.SaveChangesAsync(); return user;
    }
    public async Task<CampusUser> UpdateProfile(ClaimsPrincipal p, ProfileInput input)
    {
        var u = await Profile(p); u.FullName = Rules.Text(input.FullName, "Name", 100);
        Rules.Require(input.Bio != null && input.Bio.Length <= 500, "Bio must be at most 500 characters.");
        Rules.Require(new[] { "en", "zu", "af" }.Contains(input.Language), "Unsupported language.");
        u.Bio = input.Bio!; u.CampusLocation = Rules.Text(input.CampusLocation, "Location"); u.Language = input.Language; u.Revision++;
        await db.SaveChangesAsync(); return u;
    }
    public async Task<object> RegisterDevice(ClaimsPrincipal p, DeviceInput i)
    {
        var u = await Profile(p); var token = Rules.Text(i.Token, "Device token", 4000);
        Rules.Require(i.Platform == "android", "Unsupported device platform.");
        var registration = await db.Devices.SingleOrDefaultAsync(x => x.Token == token);
        if (registration == null) { registration = new DeviceRegistration { Token = token }; db.Devices.Add(registration); }
        registration.UserId = u.Id; registration.Platform = "android"; registration.UpdatedAt = DateTime.UtcNow;
        await db.SaveChangesAsync(); return new { registered = true };
    }
    public async Task<object> Eco(ClaimsPrincipal p)
    {
        var u = await Profile(p); return new { u.EcoScoreKg, actions = await db.EcoActions.Where(x => x.UserId == u.Id).OrderByDescending(x => x.CompletedAt).Take(50).ToListAsync(), calculation = "Delivery/carpool distance × 0.171 kg CO2/km. Second-hand books 2.5 kg, clothing 3 kg, electronics 12 kg, furniture 15 kg, other 1 kg. Estimates, not certified offsets." };
    }
    public object DeliveryView(Delivery d) => new { d.Id, d.RequesterId, d.CourierId, d.TradeId, d.ItemDescription, d.PickupBuilding, d.DropoffBuilding, d.PickupLat, d.PickupLng, d.DropoffLat, d.DropoffLng, d.WeightKg, d.RewardCents, d.WindowStart, d.WindowEnd, d.Status, d.CreatedAt };
    public async Task<object[]> Deliveries(ClaimsPrincipal p) { await Profile(p); return (await db.Deliveries.Where(x => x.Status != "CANCELLED").ToListAsync()).Select(DeliveryView).ToArray(); }
    private async Task<Delivery> Delivery(string id) => await db.Deliveries.FindAsync(id) ?? throw new CampusException("Delivery not found.", 404);
    public async Task<object> GetDelivery(ClaimsPrincipal p, string id) { await Profile(p); return DeliveryView(await Delivery(id)); }
    public async Task<object> HandoffCode(ClaimsPrincipal p, string id)
    {
        var u = await Profile(p); var d = await Delivery(id); Rules.Require(d.RequesterId == u.Id, "Only the requester may show the handoff code.", 403); return new { qrVerificationCode = d.VerificationCode };
    }
    public async Task<Delivery> CreateDelivery(ClaimsPrincipal p, DeliveryInput i)
    {
        var u = await Profile(p); Rules.Coordinates(i.PickupLat, i.PickupLng); Rules.Coordinates(i.DropoffLat, i.DropoffLng); Rules.Window(i.WindowStart, i.WindowEnd);
        Rules.Require(double.IsFinite(i.WeightKg) && i.WeightKg > 0 && i.WeightKg <= 50, "Weight must be between 0 and 50 kg."); Rules.Require(i.RewardCents >= 0 && i.RewardCents <= 100000, "Reward must be between R0 and R1000.");
        var d = new Delivery { RequesterId = u.Id, ItemDescription = Rules.Text(i.ItemDescription, "Item", 500), PickupBuilding = Rules.Text(i.PickupBuilding, "Pickup"), DropoffBuilding = Rules.Text(i.DropoffBuilding, "Drop-off"), PickupLat = i.PickupLat, PickupLng = i.PickupLng, DropoffLat = i.DropoffLat, DropoffLng = i.DropoffLng, WeightKg = i.WeightKg, RewardCents = i.RewardCents, WindowStart = i.WindowStart, WindowEnd = i.WindowEnd };
        Rules.Require(Rules.DistanceKm(i.PickupLat, i.PickupLng, i.DropoffLat, i.DropoffLng) > .01, "Choose different pickup and drop-off locations.");
        db.Deliveries.Add(d); await db.SaveChangesAsync(); return d;
    }
    public async Task<RouteOffer> CreateOffer(ClaimsPrincipal p, RouteInput i)
    {
        var u = await Profile(p); Rules.Coordinates(i.StartLat, i.StartLng); Rules.Coordinates(i.EndLat, i.EndLng); Rules.Window(i.WindowStart, i.WindowEnd);
        Rules.Require(double.IsFinite(i.CapacityKg) && i.CapacityKg > 0 && i.CapacityKg <= 50, "Capacity must be between 0 and 50 kg.");
        var o = new RouteOffer { CourierId = u.Id, StartBuilding = Rules.Text(i.StartBuilding, "Start"), EndBuilding = Rules.Text(i.EndBuilding, "End"), StartLat = i.StartLat, StartLng = i.StartLng, EndLat = i.EndLat, EndLng = i.EndLng, CapacityKg = i.CapacityKg, WindowStart = i.WindowStart, WindowEnd = i.WindowEnd };
        db.Offers.Add(o); await db.SaveChangesAsync(); return o;
    }
    public async Task<List<RouteOffer>> Matches(ClaimsPrincipal p, string id)
    {
        var u = await Profile(p); var d = await Delivery(id); Rules.Require(d.RequesterId == u.Id, "Only the requester may select couriers.", 403);
        var offers = await db.Offers.Where(x => x.CourierId != u.Id && x.WindowEnd > DateTime.UtcNow).ToListAsync();
        return offers.Where(x => Rules.Matches(d, x)).OrderBy(x => x.WindowStart).ToList();
    }
    public async Task<object> BookDelivery(ClaimsPrincipal p, string id, string offerId)
    {
        var u = await Profile(p); var d = await Delivery(id); Rules.Require(d.RequesterId == u.Id, "Only the requester may book.", 403); Rules.Require(d.Status == "ACTIVE", "Delivery is no longer available.", 409);
        var o = await db.Offers.FindAsync(offerId) ?? throw new CampusException("Courier route not found.", 404);
        Rules.Require(o.CourierId != u.Id && Rules.Matches(d, o), "This courier cannot fulfil the delivery.");
        Rules.Require(o.WindowEnd > DateTime.UtcNow, "Courier route has expired.");
        var committed = await db.Deliveries.Where(x => x.CourierId == o.CourierId && (x.Status == "MATCHED" || x.Status == "IN_PROGRESS") && x.WindowStart < d.WindowEnd && x.WindowEnd > d.WindowStart).SumAsync(x => x.WeightKg);
        Rules.Require(committed + d.WeightKg <= o.CapacityKg, "Courier capacity is already committed.", 409);
        var t = new Trade { RequesterId = u.Id, ProviderId = o.CourierId, ServiceRefId = d.Id, AgreedPriceCents = d.RewardCents };
        db.Trades.Add(t); d.CourierId = o.CourierId; d.TradeId = t.Id; d.Status = "MATCHED"; d.Revision++; o.Revision++; Event(d); await db.SaveChangesAsync(); return DeliveryView(d);
    }
    public async Task<object> Pickup(ClaimsPrincipal p, string id)
    {
        var u = await Profile(p); var d = await Delivery(id); Rules.Require(d.CourierId == u.Id, "Only the assigned courier may pick up.", 403); Rules.Require(d.Status == "MATCHED", "Delivery must be matched before pickup.", 409);
        d.Status = "IN_PROGRESS"; d.Revision++; var t = await db.Trades.FindAsync(d.TradeId); if (t != null) t.Status = "IN_PROGRESS"; Event(d); await db.SaveChangesAsync(); return DeliveryView(d);
    }
    public async Task<object> CompleteDelivery(ClaimsPrincipal p, string id, string code)
    {
        var u = await Profile(p); var d = await Delivery(id); Rules.Require(d.CourierId == u.Id, "Only the assigned courier may complete the handoff.", 403);
        Rules.Require(code != null && CryptographicOperations.FixedTimeEquals(Encoding.UTF8.GetBytes(code), Encoding.UTF8.GetBytes(d.VerificationCode)), "The QR handoff code does not match.");
        if (d.Status == "FULFILLED") return DeliveryView(d);
        Rules.Require(d.Status == "IN_PROGRESS", "Pick up the delivery before confirming handoff.", 409);
        d.Status = "FULFILLED"; d.Revision++; var t = await db.Trades.FindAsync(d.TradeId) ?? throw new CampusException("Transaction not found.", 404); t.Status = "COMPLETED";
        await CreditEco(t, Rules.DistanceKm(d.PickupLat, d.PickupLng, d.DropoffLat, d.DropoffLng) * .171, "Walked or cycled campus delivery"); Event(d); await db.SaveChangesAsync(); return DeliveryView(d);
    }
    public async Task<object> CancelDelivery(ClaimsPrincipal p, string id)
    {
        var u = await Profile(p); var d = await Delivery(id); Rules.Require(d.RequesterId == u.Id, "Only the requester may cancel.", 403); Rules.Require(d.Status is "ACTIVE" or "MATCHED", "This delivery can no longer be cancelled.", 409);
        d.Status = "CANCELLED"; d.Revision++; if (d.TradeId != null) { var t = await db.Trades.FindAsync(d.TradeId); if (t != null) t.Status = "CANCELLED"; } Event(d); await db.SaveChangesAsync(); return DeliveryView(d);
    }
    private void Event(Delivery d)
    {
        foreach (var id in new[] { d.RequesterId, d.CourierId }.Where(x => x != null).Distinct()) db.Events.Add(new DeliveryEvent { UserId = id!, DeliveryId = d.Id, Status = d.Status });
    }
    public async Task<object> Sync(ClaimsPrincipal p, OfflineInput input)
    {
        Rules.Require(input.OfflineTransactions != null && input.OfflineTransactions.Count <= 100, "Submit up to 100 offline transactions.");
        var u = await Profile(p); var count = 0; var syncedIds = new List<string>(); var failures = new List<object>();
        foreach (var i in input.OfflineTransactions!)
        {
            try
            {
                Rules.Text(i.LocalTransactionId, "Local transaction ID", 100);
                var existing = await db.OfflineReceipts.FindAsync(u.Id, i.LocalTransactionId);
                if (existing != null) { Rules.Require(existing.DeliveryId == i.DeliveryId, "Local transaction ID already belongs to a different delivery.", 409); count++; syncedIds.Add(i.LocalTransactionId); continue; }
                Rules.Require(i.CompletedAt.Kind == DateTimeKind.Utc && i.CompletedAt <= DateTime.UtcNow.AddMinutes(1), "Completion time must be a past UTC timestamp.");
                await using var transaction = await db.Database.BeginTransactionAsync();
                await CompleteDelivery(p, i.DeliveryId, i.ScannedQrHash);
                db.OfflineReceipts.Add(new OfflineReceipt { UserId = u.Id, LocalTransactionId = i.LocalTransactionId, DeliveryId = i.DeliveryId }); await db.SaveChangesAsync(); await transaction.CommitAsync(); count++; syncedIds.Add(i.LocalTransactionId);
            }
            catch (CampusException e) { failures.Add(new { i.LocalTransactionId, error = e.Message, status = e.Status }); }
        }
        return new { syncedCount = count, syncedTransactionIds = syncedIds, failedTransactions = failures };
    }
    public async Task<List<Ride>> SearchRides(ClaimsPrincipal p, string? origin, string? destination, DateTime? date)
    {
        await Profile(p); var q = db.Rides.Where(x => x.Status == "ACTIVE" && x.AvailableSeats > 0 && x.DepartureTime > DateTime.UtcNow);
        if (!string.IsNullOrWhiteSpace(origin)) { var o = origin.Trim().ToLower(); q = q.Where(x => x.Origin.ToLower().Contains(o)); }
        if (!string.IsNullOrWhiteSpace(destination)) { var d = destination.Trim().ToLower(); q = q.Where(x => x.Destination.ToLower().Contains(d)); }
        if (date.HasValue) { var day = date.Value.Date; var end = day.AddDays(1); q = q.Where(x => x.DepartureTime >= day && x.DepartureTime < end); }
        return await q.OrderBy(x => x.DepartureTime).Take(100).ToListAsync();
    }
    public async Task<Ride> CreateRide(ClaimsPrincipal p, RideInput i)
    {
        var u = await Profile(p); Rules.Require(i.DepartureTime.Kind == DateTimeKind.Utc && i.DepartureTime > DateTime.UtcNow, "Departure must be a future UTC time.");
        Rules.Require(i.AvailableSeats is >= 1 and <= 12, "Choose between 1 and 12 seats."); Rules.Require(new[] { "CAR", "SUV", "VAN" }.Contains(i.VehicleType), "Invalid vehicle type.");
        Rules.Require(i.PricePerSeatCents is >= 0 and <= 100000 && double.IsFinite(i.DistanceKm) && i.DistanceKm > 0 && i.DistanceKm <= 2000, "Invalid price or distance.");
        var ride = new Ride { DriverId = u.Id, Origin = Rules.Text(i.Origin, "Origin"), Destination = Rules.Text(i.Destination, "Destination"), DepartureTime = i.DepartureTime, AvailableSeats = i.AvailableSeats, VehicleType = i.VehicleType, PricePerSeatCents = i.PricePerSeatCents, DistanceKm = i.DistanceKm };
        Rules.Require(!ride.Origin.Equals(ride.Destination, StringComparison.OrdinalIgnoreCase), "Origin and destination must differ."); db.Rides.Add(ride); await db.SaveChangesAsync(); return ride;
    }
    public async Task<Trade> BookRide(ClaimsPrincipal p, string id)
    {
        var u = await Profile(p); var ride = await db.Rides.FindAsync(id) ?? throw new CampusException("Ride not found.", 404);
        Rules.Require(ride.DriverId != u.Id, "You cannot book your own ride."); Rules.Require(ride.AvailableSeats > 0 && ride.Status == "ACTIVE" && ride.DepartureTime > DateTime.UtcNow, "Ride is no longer available.", 409);
        Rules.Require(!await db.Trades.AnyAsync(x => x.Type == "CARPOOL" && x.ServiceRefId == id && x.RequesterId == u.Id && x.Status != "CANCELLED"), "You already booked this ride.", 409);
        ride.AvailableSeats--; ride.Revision++; if (ride.AvailableSeats == 0) ride.Status = "FULL";
        var t = new Trade { Type = "CARPOOL", RequesterId = u.Id, ProviderId = ride.DriverId, ServiceRefId = id, AgreedPriceCents = ride.PricePerSeatCents }; db.Trades.Add(t); await db.SaveChangesAsync(); return t;
    }
    public async Task<List<Listing>> Marketplace(ClaimsPrincipal p, string? category)
    {
        await Profile(p); var q = db.Listings.Where(x => x.Status == "ACTIVE"); if (!string.IsNullOrWhiteSpace(category)) q = q.Where(x => x.Category == category); return await q.Take(100).ToListAsync();
    }
    public async Task<Listing> CreateListing(ClaimsPrincipal p, ListingInput i)
    {
        var u = await Profile(p); Rules.Require(new[] { "BOOKS", "FURNITURE", "ELECTRONICS", "CLOTHING", "OTHER" }.Contains(i.Category), "Invalid category."); Rules.Require(new[] { "NEW", "LIKE_NEW", "GOOD", "FAIR" }.Contains(i.Condition), "Invalid condition."); Rules.Require(i.PriceCents >= 0 && i.PriceCents <= 10000000, "Invalid price.");
        var l = new Listing { SellerId = u.Id, Title = Rules.Text(i.Title, "Title"), Description = Rules.Text(i.Description, "Description", 2000), Category = i.Category, Condition = i.Condition, PriceCents = i.PriceCents }; db.Listings.Add(l); await db.SaveChangesAsync(); return l;
    }
    public async Task<Trade> ReserveListing(ClaimsPrincipal p, string id)
    {
        var u = await Profile(p); var l = await db.Listings.FindAsync(id) ?? throw new CampusException("Listing not found.", 404);
        Rules.Require(l.SellerId != u.Id, "You cannot reserve your own listing."); Rules.Require(l.Status == "ACTIVE", "Listing is no longer available.", 409); l.Status = "PENDING"; l.Revision++;
        var t = new Trade { Type = "MARKETPLACE", RequesterId = u.Id, ProviderId = l.SellerId, ServiceRefId = l.Id, AgreedPriceCents = l.PriceCents }; db.Trades.Add(t); await db.SaveChangesAsync(); return t;
    }
    private async Task<Trade> TradeFor(ClaimsPrincipal p, string id)
    {
        var u = await Profile(p); var t = await db.Trades.FindAsync(id) ?? throw new CampusException("Transaction not found.", 404); Rules.Require(t.RequesterId == u.Id || t.ProviderId == u.Id, "This transaction is private.", 403); return t;
    }
    public async Task<List<Trade>> Transactions(ClaimsPrincipal p) { var u = await Profile(p); return await db.Trades.Where(x => x.RequesterId == u.Id || x.ProviderId == u.Id).OrderByDescending(x => x.CreatedAt).ToListAsync(); }
    public async Task<object> CompleteTrade(ClaimsPrincipal p, string id)
    {
        var u = await Profile(p); var t = await TradeFor(p, id); Rules.Require(t.ProviderId == u.Id, "Only the provider can confirm fulfilment.", 403); Rules.Require(t.Type != "DELIVERY", "Deliveries require QR-verified handoff."); if (t.Status == "COMPLETED") return t;
        Rules.Require(t.Status == "ACCEPTED", "Transaction cannot be completed.", 409);
        double kg;
        if (t.Type == "CARPOOL") { var r = await db.Rides.FindAsync(t.ServiceRefId) ?? throw new CampusException("Ride not found.", 404); Rules.Require(DateTime.UtcNow >= r.DepartureTime, "The ride has not departed."); kg = r.DistanceKm * .171; }
        else { var l = await db.Listings.FindAsync(t.ServiceRefId) ?? throw new CampusException("Listing not found.", 404); l.Status = "SOLD"; l.Revision++; kg = l.Condition == "NEW" ? 0 : l.Category switch { "BOOKS" => 2.5, "CLOTHING" => 3, "ELECTRONICS" => 12, "FURNITURE" => 15, _ => 1 }; }
        t.Status = "COMPLETED"; await CreditEco(t, kg, t.Type == "CARPOOL" ? "Shared campus ride" : "Second-hand campus purchase"); await db.SaveChangesAsync(); return t;
    }
    private async Task CreditEco(Trade t, double kg, string description)
    {
        foreach (var id in new[] { t.RequesterId, t.ProviderId })
        {
            if (await db.EcoActions.AnyAsync(x => x.UserId == id && x.TradeId == t.Id)) continue;
            var u = await db.Users.FindAsync(id) ?? throw new CampusException("Member not found.", 404); var share = Math.Round(kg / 2, 3); u.EcoScoreKg += share; u.Revision++;
            db.EcoActions.Add(new EcoAction { UserId = id, TradeId = t.Id, SavedKg = share, Description = description });
        }
    }
    public async Task<List<ChatMessage>> Messages(ClaimsPrincipal p, string id) { await TradeFor(p, id); return await db.Messages.Where(x => x.TradeId == id).OrderBy(x => x.SentAt).Take(500).ToListAsync(); }
    public async Task<ChatMessage> SendMessage(ClaimsPrincipal p, string id, MessageInput i)
    {
        await TradeFor(p, id); var u = await Profile(p); var m = new ChatMessage { TradeId = id, SenderId = u.Id, Content = Rules.Text(i.Content, "Message", 2000) }; db.Messages.Add(m); await db.SaveChangesAsync(); return m;
    }
    public async Task<Review> Review(ClaimsPrincipal p, string id, ReviewInput i)
    {
        var t = await TradeFor(p, id); var u = await Profile(p); Rules.Require(t.Status == "COMPLETED", "Complete the transaction before reviewing."); Rules.Require(i.Rating is >= 1 and <= 5 && i.Comment != null && i.Comment.Length <= 500, "Choose 1–5 stars and a comment of at most 500 characters.");
        Rules.Require(!await db.Reviews.AnyAsync(x => x.TradeId == id && x.ReviewerId == u.Id), "You already reviewed this transaction.", 409);
        var otherId = u.Id == t.RequesterId ? t.ProviderId : t.RequesterId; var other = await db.Users.FindAsync(otherId) ?? throw new CampusException("Member not found.", 404); other.RatingSum += i.Rating; other.RatingCount++; other.Revision++;
        var r = new Review { TradeId = id, ReviewerId = u.Id, RevieweeId = otherId, Rating = i.Rating, Comment = i.Comment! }; db.Reviews.Add(r); await db.SaveChangesAsync(); return r;
    }
    public async Task<List<DeliveryEvent>> Notifications(ClaimsPrincipal p) { var u = await Profile(p); return await db.Events.Where(x => x.UserId == u.Id).OrderByDescending(x => x.CreatedAt).Take(100).ToListAsync(); }
}
