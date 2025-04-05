FROM ubuntu:22.04

# Avoid interaction
ARG DEBIAN_FRONTEND=noninteractive

# Install essential packages including LLVM and Clang
RUN apt-get update && apt-get install -y \
    curl \
    zip \
    unzip \
    wget \
    git \
    build-essential \
    libz-dev \
    llvm \
    clang \
    tree \
    && rm -rf /var/lib/apt/lists/*

# Install SDKMAN for managing SBT and GraalVM
RUN curl -s "https://get.sdkman.io" | bash

# Make SDKMAN available to all shells
RUN echo "source /root/.sdkman/bin/sdkman-init.sh" >> /etc/profile.d/sdkman.sh && \
    chmod +x /etc/profile.d/sdkman.sh

# Create bin directory
RUN mkdir -p /root/bin

# Set environment variables for SDKMAN and GraalVM
ENV SDKMAN_DIR=/root/.sdkman
ENV JAVA_HOME=/root/.sdkman/candidates/java/current
ENV PATH=$JAVA_HOME/bin:$SDKMAN_DIR/bin:$SDKMAN_DIR/candidates/java/current/bin:$SDKMAN_DIR/candidates/sbt/current/bin:/root/bin:$PATH

# Install GraalVM and SBT
RUN bash -c "source /root/.sdkman/bin/sdkman-init.sh && \
    sdk install java 21.0.3-graal && \
    sdk install sbt"

# Set working directory 
WORKDIR /workspace

# Set environment variable to identify Docker container
ENV DOCKER_CONTAINER=true

CMD ["bash"]
