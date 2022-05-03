# FROM openjdk:11-jdk as builder  
# FROM maven:3.5.4-jdk-9 as build
# WORKDIR /oci-cloudguard-api
# ADD pom.xml .
# RUN mvn clean
# RUN mvn package -DskipTests
# RUN echo "done!" 

FROM adoptopenjdk:11-jre-hotspot
RUN addgroup --system spring && adduser --ingroup spring spring
USER spring:spring
ARG JAR_FILE=target/*.jar
COPY ${JAR_FILE} cloudag.jar
ENTRYPOINT ["java","-jar","/cloudag.jar"]
