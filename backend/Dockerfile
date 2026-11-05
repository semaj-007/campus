FROM mcr.microsoft.com/dotnet/sdk:8.0 AS build
WORKDIR /src
COPY backend/global.json backend/
COPY backend/CampusRelay.Api/CampusRelay.Api.csproj backend/CampusRelay.Api/
RUN dotnet restore backend/CampusRelay.Api/CampusRelay.Api.csproj
COPY backend/CampusRelay.Api/ backend/CampusRelay.Api/
RUN dotnet publish backend/CampusRelay.Api/CampusRelay.Api.csproj -c Release -o /app/publish --no-restore

FROM mcr.microsoft.com/dotnet/aspnet:8.0 AS runtime
WORKDIR /app
COPY --from=build /app/publish .
ENV ASPNETCORE_ENVIRONMENT=Production
EXPOSE 10000
USER $APP_UID
ENTRYPOINT ["dotnet", "CampusRelay.Api.dll"]
