FROM eclipse-temurin:17.0.14_7-jdk-jammy@sha256:23f74f7840a6948f9da3870876f475c4febeb365757891a6a9ebda38a42cf53f AS sdk
RUN apt-get update && apt-get install -y --no-install-recommends curl unzip libglu1-mesa libpulse0 libnss3 libx11-6 libxcb1 libxcomposite1 libxcursor1 libxi6 libxrandr2 libxtst6 dnsmasq-base dnsutils iproute2 && rm -rf /var/lib/apt/lists/*
ENV ANDROID_HOME=/opt/android ANDROID_SDK_ROOT=/opt/android GRADLE_USER_HOME=/work/android/.gradle-cache
ENV PATH=/opt/gradle/bin:/opt/android/cmdline-tools/12.0/bin:/opt/android/platform-tools:/opt/android/emulator:$PATH
COPY fetch-sdk /usr/local/bin/fetch-sdk
RUN fetch-sdk commandlinetools-linux-11076708_latest.zip d313adb7aedccf6cf0cfca51ec180f0059f5f8f8 cmdline-tools/12.0 &&     fetch-sdk platform-35_r02.zip 0bb560a90a7a2cbd0dd8348224d518b638fe7949 platforms/android-35 &&     fetch-sdk build-tools_r35_linux.zip 2cfaa0bbb2336e9ec18ed3ecea84fa2e2af607bc build-tools/35.0.0 &&     fetch-sdk platform-tools_r37.0.1-linux.zip 477254aa5f903c15cf51001717bdf347fb6b53e0 platform-tools
RUN mkdir -p /opt/android/licenses && printf '%s\n' 8933bad161af4178b1185d1a37fbf41ea5269c55 d56f5187479451eabf01fb78af6dfcb131a6481e 24333f8a63b6825ea9c5514f83c2829b004d1fee > /opt/android/licenses/android-sdk-license
RUN curl -fsSL https://services.gradle.org/distributions/gradle-8.11.1-bin.zip -o /tmp/gradle.zip &&     echo 'f397b287023acdba1e9f6fc5ea72d22dd63669d59ed4a289a29b1a76eee151c6  /tmp/gradle.zip' | sha256sum -c - &&     unzip -q /tmp/gradle.zip -d /opt && mv /opt/gradle-8.11.1 /opt/gradle && rm /tmp/gradle.zip
RUN sdkmanager --list_installed >/dev/null
WORKDIR /work/android
FROM sdk AS emulator
RUN fetch-sdk emulator-linux_x64-16428233.zip cd7362ea55dfb86a418958138dc396e74165dd01 emulator &&     fetch-sdk sys-img/google_apis/x86_64-35_r09.zip 0103e6dab21290c4b9d16550a3ce99476f884eef system-images/android-35/google_apis/x86_64
COPY emulator-package.xml /opt/android/emulator/package.xml
RUN sdkmanager --list_installed >/dev/null
