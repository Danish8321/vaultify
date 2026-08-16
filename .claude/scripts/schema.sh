#!/usr/bin/env bash
# The ONLY sanctioned path for schema change. Data is irreversible; code is not.
#
# Two steps on purpose:
#   schema.sh generate <Name>   writes the migration and stops so it can be READ
#   schema.sh apply             applies what has already been reviewed
#
# A generated drop-and-add where a rename was intended destroys data silently.
# Read every generated migration and correct it before applying. Never edit a
# migration that has already been applied.
set -euo pipefail
cd "$(dirname "$0")/../.."

DATA_PROJ="src/Cryptum.Data/Cryptum.Data.csproj"
STARTUP_PROJ="src/Cryptum.Api/Cryptum.Api.csproj"

usage() { echo "usage: schema.sh generate <MigrationName> | schema.sh apply" >&2; exit 2; }

case "${1:-}" in
  generate)
    NAME="${2:?migration name required}"
    dotnet ef migrations add "$NAME" --project "$DATA_PROJ" --startup-project "$STARTUP_PROJ"
    echo
    echo "Migration '$NAME' generated but NOT applied."
    echo "Read it now. Confirm every rename is expressed as a rename, not a drop plus an add."
    echo "Then: schema.sh apply"
    ;;
  apply)
    dotnet ef database update --project "$DATA_PROJ" --startup-project "$STARTUP_PROJ"
    echo "schema.sh: applied"
    ;;
  *) usage ;;
esac
