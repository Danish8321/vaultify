using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace Cryptum.Data.Migrations
{
    /// <inheritdoc />
    public partial class InitialItemSchema : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.CreateTable(
                name: "Items",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "uniqueidentifier", nullable: false),
                    Owner = table.Column<Guid>(type: "uniqueidentifier", nullable: false),
                    Kind = table.Column<int>(type: "int", nullable: false),
                    Title = table.Column<string>(type: "nvarchar(200)", maxLength: 200, nullable: false),
                    Ciphertext = table.Column<byte[]>(type: "varbinary(max)", nullable: true),
                    BlobPath = table.Column<string>(type: "nvarchar(512)", maxLength: 512, nullable: true),
                    WrappedDek = table.Column<byte[]>(type: "varbinary(max)", nullable: false),
                    KekVersion = table.Column<string>(type: "nvarchar(64)", maxLength: 64, nullable: false),
                    Nonce = table.Column<byte[]>(type: "binary(12)", fixedLength: true, maxLength: 12, nullable: false),
                    CreatedAt = table.Column<DateTimeOffset>(type: "datetimeoffset", nullable: false),
                    UpdatedAt = table.Column<DateTimeOffset>(type: "datetimeoffset", nullable: false),
                    DeletedAt = table.Column<DateTimeOffset>(type: "datetimeoffset", nullable: true)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_Items", x => x.Id);
                });

            migrationBuilder.CreateIndex(
                name: "IX_Items_Owner_DeletedAt_Id",
                table: "Items",
                columns: new[] { "Owner", "DeletedAt", "Id" });
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropTable(
                name: "Items");
        }
    }
}
