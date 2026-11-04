# amd64 only: the Linux NDK and aapt2 have no arm64 builds (OrbStack runs this under Rosetta).
FROM --platform=linux/amd64 node:24-bookworm

RUN apt-get update \
  && apt-get install -y --no-install-recommends openjdk-17-jdk-headless g++ unzip \
  && rm -rf /var/lib/apt/lists/*

ENV JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 \
    ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk
ENV PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:/opt/kotlinc/bin

ARG CMDLINE_TOOLS=13114758
RUN curl -fsSL -o /tmp/tools.zip https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS}_latest.zip \
  && mkdir -p $ANDROID_HOME/cmdline-tools \
  && unzip -q /tmp/tools.zip -d $ANDROID_HOME/cmdline-tools \
  && mv $ANDROID_HOME/cmdline-tools/cmdline-tools $ANDROID_HOME/cmdline-tools/latest \
  && rm /tmp/tools.zip

# Versions from react-native libs.versions.toml; build-tools 36 is AGP's default for laser-core.
RUN yes | sdkmanager --licenses > /dev/null \
  && sdkmanager "platform-tools" "platforms;android-37.0" "build-tools;37.0.0" "build-tools;36.0.0" "ndk;27.1.12297006" "cmake;3.22.1"

ARG KOTLIN=2.2.0
RUN curl -fsSL -o /tmp/kotlin.zip https://github.com/JetBrains/kotlin/releases/download/v${KOTLIN}/kotlin-compiler-${KOTLIN}.zip \
  && unzip -q /tmp/kotlin.zip -d /opt \
  && rm /tmp/kotlin.zip

RUN java -version && kotlinc -version && g++ --version && node -v && npm -v && sdkmanager --list_installed

COPY entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod +x /usr/local/bin/entrypoint.sh \
  && git config --system --add safe.directory /work
WORKDIR /work
ENTRYPOINT ["entrypoint.sh"]
CMD ["bash"]
