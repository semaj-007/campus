using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Design;
namespace CampusRelay.Api;

// Production migrations target SQL Server. Local SQLite uses EnsureCreated in Development.
public sealed class DesignTimeDb : IDesignTimeDbContextFactory<CampusDb>
{
    public CampusDb CreateDbContext(string[] args)
    {
        var connection = Environment.GetEnvironmentVariable("ConnectionStrings__Campus")
            ?? "Server=localhost;Database=CampusRelay;Integrated Security=true;Encrypt=true";
        return new CampusDb(new DbContextOptionsBuilder<CampusDb>().UseSqlServer(connection).Options);
    }
}
