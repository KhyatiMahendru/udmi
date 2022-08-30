package com.google.udmi.util;

import static java.util.stream.Collectors.toMap;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.util.ISO8601DateFormat;
import com.google.common.base.Preconditions;
import java.io.File;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import udmi.schema.CloudIotConfig;
import udmi.schema.CloudModel.Auth_type;
import udmi.schema.EndpointConfiguration;
import udmi.schema.Envelope;
import udmi.schema.GatewayModel;
import udmi.schema.Metadata;

public class SiteModel {

  private static final String ID_FORMAT = "projects/%s/locations/%s/registries/%s/devices/%s";
  private static final String KEY_SITE_PATH_FORMAT = "%s/devices/%s/%s_private.pkcs8";
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
      .enable(SerializationFeature.INDENT_OUTPUT)
      .setDateFormat(new ISO8601DateFormat())
      .setSerializationInclusion(JsonInclude.Include.NON_NULL);
  private static final String DEFAULT_ENDPOINT_HOSTNAME = "mqtt.googleapis.com";
  private static final Pattern ID_PATTERN = Pattern.compile(
      "projects/(.*)/locations/(.*)/registries/(.*)/devices/(.*)");

  final String sitePath;
  private Map<String, Metadata> allMetadata;
  private CloudIotConfig cloudIotConfig;

  public SiteModel(String sitePath) {
    this.sitePath = sitePath;
  }

  public static EndpointConfiguration makeEndpointConfig(String projectId,
      CloudIotConfig cloudIotConfig, String deviceId) {
    EndpointConfiguration endpoint = new EndpointConfiguration();
    endpoint.client_id = getClientId(projectId,
        cloudIotConfig.cloud_region, cloudIotConfig.registry_id, deviceId);
    endpoint.hostname = DEFAULT_ENDPOINT_HOSTNAME;
    return endpoint;
  }

  public static String getClientId(String projectId, String cloudRegion, String registryId,
      String deviceId) {
    return String.format(ID_FORMAT, projectId, cloudRegion, registryId, deviceId);
  }

  private static CloudIotConfig makeCloudIotConfig(Envelope attributes) {
    CloudIotConfig cloudIotConfig = new CloudIotConfig();
    cloudIotConfig.registry_id = Preconditions.checkNotNull(attributes.deviceRegistryId,
        "deviceRegistryId");
    cloudIotConfig.cloud_region = Preconditions.checkNotNull(attributes.deviceRegistryLocation,
        "deviceRegistryLocation");
    return cloudIotConfig;
  }

  public static EndpointConfiguration makeEndpointConfig(Envelope attributes) {
    CloudIotConfig cloudIotConfig = makeCloudIotConfig(attributes);
    return makeEndpointConfig(attributes.projectId, cloudIotConfig, attributes.deviceId);
  }

  /**
   * Parse a GCP clientId string into component parts including project, etc...
   *
   * @param clientId client id to parse
   * @return bucket of parameters
   */
  public static ClientInfo parseClientId(String clientId) {
    if (clientId == null) {
      throw new IllegalArgumentException("client_id not specified");
    }
    Matcher matcher = ID_PATTERN.matcher(clientId);
    if (!matcher.matches()) {
      throw new IllegalArgumentException(
          String.format("client_id %s does not match pattern %s", clientId, ID_PATTERN.pattern()));
    }
    ClientInfo clientInfo = new ClientInfo();
    clientInfo.projectId = matcher.group(1);
    clientInfo.cloudRegion = matcher.group(2);
    clientInfo.registryId = matcher.group(3);
    clientInfo.deviceId = matcher.group(4);
    return clientInfo;
  }

  public EndpointConfiguration makeEndpointConfig(String projectId, String deviceId) {
    return makeEndpointConfig(projectId, cloudIotConfig, deviceId);
  }

  private Set<String> getAllDevices() {
    Preconditions.checkState(sitePath != null, "sitePath not defined");
    File devicesFile = new File(new File(sitePath), "devices");
    File[] files = Preconditions.checkNotNull(devicesFile.listFiles(), "no files in site devices/");
    return Arrays.stream(files).map(File::getName).collect(Collectors.toSet());
  }

  private void loadAllDeviceMetadata() {
    allMetadata = getAllDevices().stream().collect(toMap(key -> key, this::loadDeviceMetadata));
  }

  private Metadata loadDeviceMetadata(String deviceId) {
    Preconditions.checkState(sitePath != null, "sitePath not defined");
    File devicesFile = new File(new File(sitePath), "devices");
    File deviceDir = new File(devicesFile, deviceId);
    File deviceMetadataFile = new File(deviceDir, "metadata.json");
    try {
      return OBJECT_MAPPER.readValue(deviceMetadataFile, Metadata.class);
    } catch (Exception e) {
      throw new RuntimeException(
          "While reading metadata file " + deviceMetadataFile.getAbsolutePath(), e);
    }
  }

  public Metadata getMetadata(String deviceId) {
    return allMetadata.get(deviceId);
  }

  public void forEachDevice(BiConsumer<String, Metadata> consumer) {
    allMetadata.forEach(consumer);
  }

  private void loadSiteConfig() {
    Preconditions.checkState(sitePath != null,
        "sitePath not defined in configuration");
    File cloudConfig = new File(new File(sitePath), "cloud_iot_config.json");
    try {
      cloudIotConfig = OBJECT_MAPPER.readValue(cloudConfig, CloudIotConfig.class);
    } catch (Exception e) {
      throw new RuntimeException("While reading config file " + cloudConfig.getAbsolutePath(), e);
    }
  }

  public void initialize() {
    loadSiteConfig();
    loadAllDeviceMetadata();
  }

  public Auth_type getAuthType(String deviceId) {
    return allMetadata.get(deviceId).cloud.auth_type;
  }

  public String getDeviceKeyFile(String deviceId) {
    String gatewayId = findGateway(deviceId);
    String keyDevice = gatewayId != null ? gatewayId : deviceId;
    return String.format(KEY_SITE_PATH_FORMAT, sitePath,
        keyDevice, getDeviceKeyPrefix(keyDevice));
  }

  private String findGateway(String deviceId) {
    GatewayModel gateway = getMetadata(deviceId).gateway;
    return gateway != null ? gateway.gateway_id : null;
  }

  private String getDeviceKeyPrefix(String targetId) {
    Auth_type auth_type = getMetadata(targetId).cloud.auth_type;
    return auth_type.value().startsWith("RS") ? "rsa" : "ec";
  }

  /**
   * Get the site registry name.
   *
   * @return site registry
   */
  public String getRegistryId() {
    return cloudIotConfig.registry_id;
  }

  /**
   * Get the update topic for the site, if defined.
   *
   * @return update topic
   */
  public String getUpdateTopic() {
    return cloudIotConfig.update_topic;
  }

  public static class ClientInfo {

    public String cloudRegion;
    public String projectId;
    public String registryId;
    public String deviceId;
  }
}