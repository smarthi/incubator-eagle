/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.apache.eagle.topology.storm;

import backtype.storm.task.OutputCollector;
import backtype.storm.task.TopologyContext;
import backtype.storm.topology.OutputFieldsDeclarer;
import backtype.storm.topology.base.BaseRichBolt;
import backtype.storm.tuple.Tuple;
import org.apache.eagle.log.base.taggedlog.TaggedLogAPIEntity;
import org.apache.eagle.log.entity.GenericServiceAPIResponseEntity;
import org.apache.eagle.service.client.EagleServiceClientException;
import org.apache.eagle.service.client.EagleServiceConnector;
import org.apache.eagle.service.client.IEagleServiceClient;
import org.apache.eagle.service.client.impl.EagleServiceClientImpl;
import org.apache.eagle.topology.TopologyCheckAppConfig;
import org.apache.eagle.topology.TopologyConstants;
import org.apache.eagle.topology.extractor.TopologyEntityParserResult;
import org.apache.eagle.topology.entity.TopologyBaseAPIEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;

public class TopologyDataPersistBolt extends BaseRichBolt {

    private TopologyCheckAppConfig config;
    private IEagleServiceClient client;
    private OutputCollector collector;

    private static final Logger LOG = LoggerFactory.getLogger(TopologyDataPersistBolt.class);

    public TopologyDataPersistBolt(TopologyCheckAppConfig config) {
        this.config = config;
    }

    @Override
    public void prepare(Map stormConf, TopologyContext context, OutputCollector collector) {
        this.client = new EagleServiceClientImpl(new EagleServiceConnector(this.config.config));
        this.collector = collector;
    }

    @Override
    public void execute(Tuple input) {
        if (input == null) {
            return;
        }
        String serviceName = input.getStringByField(TopologyConstants.SERVICE_NAME_FIELD);
        TopologyEntityParserResult result = (TopologyEntityParserResult) input.getValueByField(TopologyConstants.TOPOLOGY_DATA_FIELD);
        Set<String> availableHostnames = new HashSet<String>();
        List<TopologyBaseAPIEntity> entitiesForDeletion = new ArrayList<>();
        List<TopologyBaseAPIEntity> entitiesToWrite = new ArrayList<>();

        filterEntitiesToWrite(entitiesToWrite, availableHostnames, result.getMasterNodes());
        filterEntitiesToWrite(entitiesToWrite, availableHostnames, result.getSlaveNodes());

        String query = String.format("%s[@site=\"%s\"]{*}", serviceName, this.config.dataExtractorConfig.site);
        try {
            GenericServiceAPIResponseEntity<TopologyBaseAPIEntity> response = client.search().query(query).pageSize(Integer.MAX_VALUE).send();
            if (response.isSuccess() && response.getObj() != null) {
                for (TopologyBaseAPIEntity entity : response.getObj()) {
                    if (!availableHostnames.contains(generateKey(entity))) {
                        entitiesForDeletion.add(entity);
                    }
                }
            }
            deleteEntities(entitiesForDeletion, serviceName);
            writeEntities(entitiesToWrite, serviceName);
            writeEntities(result.getMetrics(), serviceName);
            this.collector.ack(input);
        } catch (Exception e) {
            e.printStackTrace();
            this.collector.fail(input);
        }
    }

    private void filterEntitiesToWrite(List<TopologyBaseAPIEntity> entitiesToWrite, Set<String> availableHostnames, List<TopologyBaseAPIEntity> entities) {
        for (TopologyBaseAPIEntity entity : entities) {
            availableHostnames.add(generateKey(entity));
            entitiesToWrite.add(entity);
        }
    }

    @Override
    public void declareOutputFields(OutputFieldsDeclarer declarer) {

    }

    private void deleteEntities(List<TopologyBaseAPIEntity> entities, String serviceName) {
        try {
            GenericServiceAPIResponseEntity response = client.delete(entities);
            if (!response.isSuccess()) {
                LOG.error("Got exception from eagle service: " + response.getException());
            } else {
                LOG.info("Successfully delete {} entities for {}", entities.size(), serviceName);
            }
        } catch (EagleServiceClientException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
        entities.clear();
    }

    private void writeEntities(List<? extends TaggedLogAPIEntity> entities, String serviceName) {
        try {
            GenericServiceAPIResponseEntity response = client.create(entities);
            if (!response.isSuccess()) {
                LOG.error("Got exception from eagle service: " + response.getException());
            } else {
                LOG.info("Successfully wrote {} entities for {}", entities.size(), serviceName);
            }
        } catch (Exception e) {
            LOG.error("cannot create entities successfully", e);
        }
        entities.clear();
    }

    private String generateKey(TopologyBaseAPIEntity entity) {
        return String.format("%s-%s-%s-%s", entity.getTags().get(TopologyConstants.SITE_TAG),
                entity.getTags().get(TopologyConstants.RACK_TAG), entity.getTags().get(TopologyConstants.HOSTNAME_TAG),
                entity.getTags().get(TopologyConstants.ROLE_TAG));
    }

}
