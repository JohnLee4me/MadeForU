FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app
COPY MarketServer.java Untitled-1.html manifest.webmanifest service-worker.js icon.svg ./
RUN mkdir -p classes && javac -encoding UTF-8 -d classes MarketServer.java

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/classes ./classes
COPY Untitled-1.html manifest.webmanifest service-worker.js icon.svg madeforu-logo.png assetlinks.json ./
RUN mkdir -p .well-known && cp assetlinks.json .well-known/assetlinks.json
ENV PORT=10000
EXPOSE 10000
CMD ["sh", "-c", "java -cp /app/classes MarketServer \"$PORT\""]
