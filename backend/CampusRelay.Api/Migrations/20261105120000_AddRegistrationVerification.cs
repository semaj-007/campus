using System;
using CampusRelay.Api;
using Microsoft.EntityFrameworkCore.Migrations;
using Microsoft.EntityFrameworkCore.Infrastructure;

#nullable disable

namespace CampusRelay.Api.Migrations;

[DbContext(typeof(CampusDb))]
[Migration("20261105120000_AddRegistrationVerification")]
public partial class AddRegistrationVerification : Migration
{
    protected override void Up(MigrationBuilder migrationBuilder)
    {
        migrationBuilder.AddColumn<string>(name: "RegistrationStatus", table: "Users", type: "nvarchar(max)", nullable: false, defaultValue: "APPROVED");
        migrationBuilder.CreateTable(
            name: "RegistrationProofs",
            columns: table => new
            {
                Id = table.Column<string>(type: "nvarchar(450)", nullable: false),
                UserId = table.Column<string>(type: "nvarchar(max)", nullable: false),
                StudentNumber = table.Column<string>(type: "nvarchar(max)", nullable: false),
                FileName = table.Column<string>(type: "nvarchar(max)", nullable: false),
                ContentType = table.Column<string>(type: "nvarchar(max)", nullable: false),
                Content = table.Column<byte[]>(type: "varbinary(max)", nullable: false),
                SubmittedAt = table.Column<DateTimeOffset>(type: "datetimeoffset", nullable: false)
            },
            constraints: table => table.PrimaryKey("PK_RegistrationProofs", x => x.Id));
        migrationBuilder.CreateIndex(name: "IX_RegistrationProofs_UserId", table: "RegistrationProofs", column: "UserId", unique: true);
    }

    protected override void Down(MigrationBuilder migrationBuilder)
    {
        migrationBuilder.DropTable(name: "RegistrationProofs");
        migrationBuilder.DropColumn(name: "RegistrationStatus", table: "Users");
    }
}
