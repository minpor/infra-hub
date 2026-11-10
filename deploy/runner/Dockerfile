FROM eclipse-temurin:21-jdk-jammy AS jdk
FROM node:22-bookworm-slim
COPY --from=jdk /opt/java/openjdk/ /opt/java/openjdk/
ENV JAVA_HOME=/opt/java/openjdk PATH=/opt/java/openjdk/bin:$PATH
RUN apt-get update && apt-get install -y --no-install-recommends python3 python3-yaml nginx ca-certificates chromium xvfb xauth libx11-6 libxext6 libxrender1 libxtst6 libxi6 libfreetype6 fontconfig fonts-noto-color-emoji && rm -rf /var/lib/apt/lists/*
COPY dependency-proxy.py proxy-bridge.py run-build bundle-web.py bundle-android.py build-aukni-web.py local-nginx-test.py /opt/hub/
RUN chmod 755 /opt/hub/run-build
COPY android-sdk/ /opt/android-sdk/
ENV ANDROID_HOME=/opt/android-sdk ANDROID_SDK_ROOT=/opt/android-sdk
USER 10001:10001
WORKDIR /work
