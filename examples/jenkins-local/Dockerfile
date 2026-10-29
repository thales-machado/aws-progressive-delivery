FROM jenkins/jenkins:lts-jdk17

ARG TERRAFORM_VERSION=1.9.8
USER root
RUN apt-get update \
    && apt-get install -y --no-install-recommends awscli jq unzip curl ca-certificates \
    && curl -fsSL "https://releases.hashicorp.com/terraform/${TERRAFORM_VERSION}/terraform_${TERRAFORM_VERSION}_linux_amd64.zip" -o /tmp/tf.zip \
    && unzip /tmp/tf.zip -d /usr/local/bin terraform \
    && rm -rf /tmp/tf.zip /var/lib/apt/lists/* \
    && git config --system --add safe.directory '*'
USER jenkins

COPY plugins.txt /usr/share/jenkins/ref/plugins.txt
RUN jenkins-plugin-cli --plugin-file /usr/share/jenkins/ref/plugins.txt
COPY casc/jenkins.yaml /usr/share/jenkins/ref/casc/jenkins.yaml
ENV CASC_JENKINS_CONFIG=/usr/share/jenkins/ref/casc/jenkins.yaml \
    JAVA_OPTS="-Djenkins.install.runSetupWizard=false"
