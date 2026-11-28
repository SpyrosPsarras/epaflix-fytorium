FROM reactnativecommunity/react-native-android@sha256:e817ea1959719c97c05adbc55348ebe21d286cec2caed1f18ad3c4b88c08cbf7 AS build
RUN npm install -g pnpm@10.18.3 >/dev/null 2>&1 && apt-get update && apt-get install -y --no-install-recommends dnsmasq-base dnsutils iproute2 sqlite3 unzip && rm -rf /var/lib/apt/lists/*
ENV GRADLE_USER_HOME=/src/cache/gradle PNPM_HOME=/src/cache/pnpm npm_config_store_dir=/src/cache/pnpm-store
FROM build AS emulator
RUN apt-get update && apt-get install -y --no-install-recommends postgresql-client && rm -rf /var/lib/apt/lists/*
COPY fetch-sdk /usr/local/bin/fetch-sdk
RUN fetch-sdk emulator-linux_x64-16428233.zip cd7362ea55dfb86a418958138dc396e74165dd01 emulator && \
    fetch-sdk sys-img/google_apis/x86_64-35_r09.zip 0103e6dab21290c4b9d16550a3ce99476f884eef system-images/android-35/google_apis/x86_64
COPY emulator-package.xml /opt/android/emulator/package.xml
RUN curl --retry 3 -fsSL https://github.com/mobile-dev-inc/Maestro/releases/download/cli-2.12.1/maestro.zip -o /tmp/maestro.zip && \
    echo '54dbb0716425626604973cbb374c585ca30272375d9ce15177a1152765ddd9d7  /tmp/maestro.zip' | sha256sum -c - && \
    unzip -q /tmp/maestro.zip -d /opt && rm /tmp/maestro.zip
ENV PATH=/opt/android/emulator:/opt/maestro/bin:$PATH MAESTRO_CLI_NO_ANALYTICS=1
