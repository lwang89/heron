//  Copyright 2017 Twitter. All rights reserved.
//
//  Licensed under the Apache License, Version 2.0 (the "License");
//  you may not use this file except in compliance with the License.
//  You may obtain a copy of the License at
//
//  http://www.apache.org/licenses/LICENSE-2.0
//
//  Unless required by applicable law or agreed to in writing, software
//  distributed under the License is distributed on an "AS IS" BASIS,
//  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//  See the License for the specific language governing permissions and
//  limitations under the License.
package com.twitter.heron.uploader.bk;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.util.logging.Logger;

import org.apache.distributedlog.AppendOnlyStreamWriter;
import org.apache.distributedlog.DistributedLogConfiguration;
import org.apache.distributedlog.api.DistributedLogManager;
import org.apache.distributedlog.api.namespace.Namespace;
import org.apache.distributedlog.api.namespace.NamespaceBuilder;

import com.twitter.heron.spi.common.Config;
import com.twitter.heron.spi.common.Context;
import com.twitter.heron.spi.uploader.IUploader;
import com.twitter.heron.spi.uploader.UploaderException;
import com.twitter.heron.spi.utils.UploaderUtils;

/**
 * A bookkeeper based uploader implementation.
 */
public class BKUploader implements IUploader {

  private static final Logger LOG = Logger.getLogger(BKUploader.class.getName());

  private String destTopologyNamespaceURI;
  private Config config;
  private String topologyPackageLocation;
  private String packageName;
  private URI packageURI;

  // the namespace instance
  private Namespace namespace;

  @Override
  public void initialize(Config upConfig) {
    this.config = upConfig;

    // initialize the distributedlog namespace for uploading jars
    try {
      initializeNamespace();
    } catch (IOException ioe) {
      throw new RuntimeException(
          "Failed to initialize distributedlog namespace for uploading topologies", ioe);
    }

    this.destTopologyNamespaceURI = BKContext.dlTopologiesNamespaceURI(config);
    this.topologyPackageLocation = Context.topologyPackageFile(config);

    // name of the destination file is the same as the base name of the topology package file.
    this.packageName =
        UploaderUtils.generateFilename(
            Context.topologyName(config), Context.role(config));

    this.packageURI = URI.create(String.format("%s/%s", destTopologyNamespaceURI, packageName));
  }

  private void initializeNamespace() throws IOException {
    DistributedLogConfiguration conf = new DistributedLogConfiguration()
        .setWriteLockEnabled(true)
        .setOutputBufferSize(256 * 1024)                  // 256k
        .setPeriodicFlushFrequencyMilliSeconds(0)         // disable periodical flush
        .setImmediateFlushEnabled(false)                  // disable immediate flush
        .setLogSegmentRollingIntervalMinutes(0)           // disable time-based rolling
        .setMaxLogSegmentBytes(Long.MAX_VALUE)            // disable size-based rolling
        .setExplicitTruncationByApplication(true)         // no auto-truncation
        .setRetentionPeriodHours(Integer.MAX_VALUE)       // long retention
        .setUseDaemonThread(true);                        // use daemon thread

    URI uri = URI.create(BKContext.dlTopologiesNamespaceURI(this.config));
    LOG.info(String.format(
        "Initializing distributedlog namespace for uploading topologies : %s",
        uri));

    this.namespace = NamespaceBuilder.newBuilder()
        .clientId("heron-uploader")
        .conf(conf)
        .uri(uri)
        .build();
  }

  // Utils method
  protected boolean isLocalFileExists(String file) {
    return new File(file).isFile();
  }

  protected OutputStream openOutputStream(String packageName) throws IOException {
    DistributedLogManager dlm = namespace.openLog(packageName);
    AppendOnlyStreamWriter writer = dlm.getAppendOnlyStreamWriter();
    return new DLOutputStream(dlm, writer);
  }

  @Override
  public URI uploadPackage() throws UploaderException {
    try {
      return doUploadPackage();
    } catch (IOException ioe) {
      throw new UploaderException("Encountered exceptions on uploading the package '"
          + packageName + "'", ioe);
    }
  }

  private URI doUploadPackage() throws IOException {
    // first, check if the topology package exists
    if (!isLocalFileExists(topologyPackageLocation)) {
      throw new UploaderException(
        String.format("Expected topology package file to be uploaded does not exist at '%s'",
            topologyPackageLocation));
    }

    // if the dest directory does not exist, create it.
    if (namespace.logExists(packageName)) {
      // if the destination file exists, write a log message
      LOG.info(String.format("Target topology file already exists at '%s'. Overwriting it now",
          packageURI.toString()));
      namespace.deleteLog(packageName);
    }

    // copy the topology package to target working directory
    LOG.info(String.format("Uploading topology package at '%s' to target DL at '%s'",
        topologyPackageLocation, packageURI.toString()));

    OutputStream out = openOutputStream(packageName);
    try {
      UploaderUtils.copyToOutputStream(topologyPackageLocation, out);
    } finally {
      out.close();
    }
    return packageURI;
  }

  @Override
  public boolean undo() {
    try {
      namespace.deleteLog(packageName);
      return true;
    } catch (IOException e) {
      LOG.warning(String.format(
          "Failed to delete the package '%s' to undo uploading process", packageName));
      return false;
    }
  }

  @Override
  public void close() {
    if (null == namespace) {
      return;
    }
    namespace.close();
  }
}