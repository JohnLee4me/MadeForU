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

Sellers set their shop name once in **Seller Profile** and choose their shop location with device GPS or by searching and tapping the map. The app keeps coordinates behind the scenes and shows a readable place name instead. **Confirm location & Save** saves the selected pin and profile without clearing the selection. Saved locations remain selected when the profile is reopened; a new GPS capture or map selection changes the pin. There is no flower-selling step in Seller Profile; sellers create listings from the seller dashboard or Product navigation. If a seller opens the listing form before completing their profile, saving the location returns them to that listing form. Customers can see a map indicator on flower listings and a map preview on product and seller profile pages. GPS requires browser location permission and a secure context (HTTPS or localhost). The map and place search use OpenStreetMap services. New flower listings automatically use the saved shop name and location.

Every flower listing requires a seller-uploaded flower photo. Sellers can drag and zoom a 4:3 crop to frame wide or large photos; the original is not changed. The browser exports a clear JPEG crop up to 1800×1350 and 2 MB, and the server validates that image before accepting the listing. The photo appears in product cards, product details, checkout, orders, and seller listings.

Seller listings and profiles are separated by the Google account identity, not by the visible shop name. Customers see the shared marketplace, but a shop page shows only its selected seller’s listings; each seller’s dashboard, orders, and earnings are scoped to that seller. Customer orders and chats are scoped to the signed-in customer identity. To switch between seller and customer accounts, sign out from Profile and choose the other account type at sign-in; attempting a role-only page while signed in keeps the current account open and explains the required switch.

The bottom navigation follows the signed-in account role on every screen, including Messages, product details, and seller profile screens.

Customers can search seller/shop names and open a seller profile from the seller directory or any product detail. Seller profiles show that shop’s listings and customer reviews. Customers can post one rating and optional comment per seller per order only after that seller marks their items delivered. Reviews are server-created, tied to the verified customer and order, and have no seller edit/delete action.

The bouquet builder shows a live bouquet illustration for wrapper and ribbon colors, with the color selections beneath each preview tile. Its selected flower, size, wrapper, ribbon, message style, and card note are shown at checkout and saved with the order, alongside the customer’s delivery address, delivery method, and payment method; sellers can view those order details when preparing the bouquet.

Customers may use GPS at checkout to attach a delivery pin; sellers see the supplied address and map link in their order list. Payment choices are limited to Cash on Delivery and a simulated GCash sandbox confirmation. The GCash option does not contact a payment provider or move real money. Sellers set their own flower prices. The app waives its ₱50-per-flower marketplace charge for each customer’s first five orders and adds it from order six onward. This is a marketplace charge, not a configured or remitted statutory tax; confirm applicable tax obligations separately before using the app for real sales.

Seller accounts can activate a simulated ₱400 monthly subscription from Seller Profile. Activation grants one calendar month of listing access; publishing is blocked when the subscription expires. This is a test-only simulation and does not charge a payment method. Customers can follow shops from public seller profiles; the customer profile shows the number of shops followed, and seller profiles show their average review rating.

The seller dashboard shows the current month and a January-to-December chart of the seller’s flower units sold, with monthly counts and a total flowers-sold metric. Revenue and units are calculated only from that seller’s items in each order.

The app shell can load offline after its first visit, but checkout and seller actions require a connection to the server. The catalog starts empty: customer listings appear only after a seller publishes them. Seller profiles, including the saved shop pin, and marketplace purchase counts, subscriptions, and follows are stored in `~/.madeforu/` by default and survive local server restarts. Set `MADEFORU_DATA_DIR` to choose another data directory. On Render or another ephemeral host, mount persistent storage and set this variable to its mount path. Products, orders, reviews, and sign-in sessions are still held in server memory and reset when the service restarts; connect persistent storage for those records before using this demo for real orders.

At checkout, customers may attach a Google Drive photo or video. The server checks that the Drive link is publicly accessible and serves image/video content before accepting the order; private or non-media links are rejected. Sellers see verified gift media with the order and can print or save its QR code. QR codes are generated in the browser; the QRCode library is loaded from cdnjs.

Each order creates a customer/seller chat thread, and the seller’s chat starts with an automatic thank-you message. Customers can open the seller chat from order confirmation or order details; sellers can open the matching customer chat from their order list and reply in the conversation. Open conversations refresh automatically every three seconds while visible. Chat messages are held in server memory and reset when the service restarts.
