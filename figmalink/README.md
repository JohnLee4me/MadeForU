# MadeForU

MadeForU is a mobile-first flower bouquet storefront and seller dashboard, served by a small Java HTTP server.

## Run locally

Install Java 17 or newer, then run these commands from this folder:

```powershell
$env:GOOGLE_CLIENT_ID = "YOUR_GOOGLE_WEB_CLIENT_ID"
javac -encoding UTF-8 -d "$env:TEMP\madeforu-classes" MarketServer.java
java -cp "$env:TEMP\madeforu-classes" MarketServer
```

Before starting the server, create a Google OAuth 2.0 **Web application** client in [Google Cloud Console](https://console.cloud.google.com/auth/clients). Set `http://localhost:8080` as an **Authorized JavaScript origin**, then copy the client ID into the command above. If the Google consent screen is in testing mode, add your Google account as a test user. Do not put a Google password in the app or share one.

Open `http://localhost:8080` and choose **Continue with Google**. If the server is already running, stop it with Ctrl+C and restart it after setting `GOOGLE_CLIENT_ID`; the server reads the setting when it starts. To use the app on a phone, deploy it to HTTPS as described below; Google's sign-in origin is not configured for a local network IP address.

## Publish online

The included `Dockerfile` and `render.yaml` are ready for Render:

1. Put the project files in a GitHub repository.
2. In Render, choose **New → Blueprint**, connect that repository, and deploy the `render.yaml` service.
3. Create an OAuth 2.0 **Web application** client in Google Cloud Console. Add your deployed HTTPS origin (and `http://localhost:8080` for local testing) to **Authorized JavaScript origins**.
4. In Render, set the `GOOGLE_CLIENT_ID` environment variable to that client ID, then deploy/restart the service. Google sign-in will remain unavailable until this is configured. No customer account is built in; customers and sellers sign in with their own Google accounts and select their role.
5. Open the HTTPS URL Render gives you on a phone. HTTPS is required for installable PWA features outside localhost.

On iPhone, open the URL in Safari and choose **Share → Add to Home Screen**. On Android, open it in Chrome and choose **Install app** or **Add to Home screen**.

The app shell can load offline after its first visit, but checkout and seller actions require a connection to the server. Current products, orders, seller settings, and sign-in sessions are held in server memory and reset when the service restarts; connect persistent storage before using this demo for real orders. The initial flower listings are sample marketplace data, not pre-created customer or seller accounts.
