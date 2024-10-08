/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.runtime.heartbeat;

import org.apache.flink.runtime.clusterframework.types.ResourceID;

/**
 * Interface for components which can be sent heartbeats and from which one can request a heartbeat
 * response. Both the heartbeat response as well as the heartbeat request can carry a payload. This
 * payload is reported to the heartbeat target and contains additional information. The payload can
 * be empty which is indicated by a null value.
 * clouding: 这事一个代表发送心跳和接受心跳的接口。
 *          心跳响应和心跳请求，都可以带一个payload，payload是汇报给目标心跳，可以附带一些信息，也可以是个空的null
 *
 * @param <I> Type of the payload which is sent to the heartbeat target
 */
public interface HeartbeatTarget<I> {

    /**
     * Sends a heartbeat response to the target. Each heartbeat response can carry a payload which
     * contains additional information for the heartbeat target.
     *
     * @param heartbeatOrigin Resource ID identifying the machine for which a heartbeat shall be
     *     reported. 这事个标识，资源id
     * @param heartbeatPayload Payload of the heartbeat. Null indicates an empty payload.
     */
    void receiveHeartbeat(ResourceID heartbeatOrigin, I heartbeatPayload);

    /**
     * Requests a heartbeat from the target. Each heartbeat request can carry a payload which
     * contains additional information for the heartbeat target.
     *
     * @param requestOrigin Resource ID identifying the machine issuing the heartbeat request.
     * @param heartbeatPayload Payload of the heartbeat request. Null indicates an empty payload.
     */
    void requestHeartbeat(ResourceID requestOrigin, I heartbeatPayload);
}
