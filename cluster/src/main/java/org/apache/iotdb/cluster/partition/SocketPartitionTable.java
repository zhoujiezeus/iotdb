/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.  See the NOTICE file distributed with this work for additional information regarding copyright ownership.  The ASF licenses this file to you under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the License.  You may obtain a copy of the License at      http://www.apache.org/licenses/LICENSE-2.0  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the License for the specific language governing permissions and limitations under the License.
 */

package org.apache.iotdb.cluster.partition;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.iotdb.cluster.config.ClusterDescriptor;
import org.apache.iotdb.cluster.rpc.thrift.Node;
import org.apache.iotdb.cluster.utils.SerializeUtils;

public class SocketPartitionTable implements PartitionTable {

  private static final int REPLICATION_NUM =
      ClusterDescriptor.getINSTANCE().getConfig().getReplicationNum();
  private static final long PARTITION_INTERVAL =
      ClusterDescriptor.getINSTANCE().getConfig().getPartitionInterval();
  private static final int SOCKET_NUM = 10000;

  private List<Node> nodeRing = new ArrayList<>();
  private Map<Node, List<Integer>> nodeSocketMap = new ConcurrentHashMap<>();
  private Map<Integer, Node> socketNodeMap = new ConcurrentHashMap<>();

  // the data groups which the VNode of this node belongs to
  private List<PartitionGroup> localGroups;
  private Node thisNode;

  public SocketPartitionTable(Node thisNode) {
    this.thisNode = thisNode;
  }

  public SocketPartitionTable(Collection<Node> nodes, Node thisNode) {
    this.thisNode = thisNode;
    init(nodes);
  }

  private void init(Collection<Node> nodes) {
    nodeRing.addAll(nodes);
    nodeRing.sort(Comparator.comparingInt(Node::getNodeIdentifier));
    localGroups = getPartitionGroups(thisNode);
    assignPartitions();
  }

  private void assignPartitions() {
    int nodeNum = nodeRing.size();
    int socketsPerNode = SOCKET_NUM / nodeNum;
    for (Node node : nodeRing) {
      List<Integer> nodeSockets = new ArrayList<>();
      nodeSocketMap.put(node, nodeSockets);
    }

    for (int i = 0; i < SOCKET_NUM; i++) {
      int nodeIdx = i / socketsPerNode;
      if (nodeIdx >= nodeNum) {
        nodeIdx--;
      }
      nodeSocketMap.get(nodeRing.get(nodeIdx)).add(i);
    }

    for (Entry<Node, List<Integer>> entry : nodeSocketMap.entrySet()) {
      for (Integer socket : entry.getValue()) {
        socketNodeMap.put(socket, entry.getKey());
      }
    }
  }


  // find REPLICATION_NUM groups that a node is in
  private List<PartitionGroup> getPartitionGroups(Node node) {
    List<PartitionGroup> ret = new ArrayList<>();

    int nodeIndex = nodeRing.indexOf(node);
    for (int i = 0; i < REPLICATION_NUM; i++) {
      int startIndex = nodeIndex - i;
      PartitionGroup partitionGroup = new PartitionGroup();
      boolean crossZero = false;
      if (startIndex < 0) {
        crossZero = true;
        startIndex = startIndex + nodeRing.size();
      }

      if (crossZero) {
        int remaining = REPLICATION_NUM - (nodeRing.size() - startIndex);
        partitionGroup.addAll(nodeRing.subList(startIndex, nodeRing.size()));
        partitionGroup.addAll(nodeRing.subList(0, remaining));
      } else {
        int endIndex = startIndex + REPLICATION_NUM;
        if (endIndex > nodeRing.size()) {
          partitionGroup.addAll(nodeRing.subList(startIndex, nodeRing.size()));
          partitionGroup.addAll(nodeRing.subList(0, endIndex - nodeRing.size()));
        } else {
          partitionGroup.addAll(nodeRing.subList(startIndex, endIndex));
        }
      }
      ret.add(partitionGroup);
    }
    return ret;
  }

  @Override
  public PartitionGroup getHeaderGroup(Node node) {
    PartitionGroup ret = new PartitionGroup();
    ret.add(node);

    int nodeIndex = nodeRing.indexOf(node);
    // find the next REPLICATION_NUM - 1 physical nodes
    int remaining = REPLICATION_NUM - 1;
    int currIndex = nodeIndex;
    while (remaining > 0) {
      // move to the next node
      currIndex = nextIndex(currIndex);
      Node curr = nodeRing.get(currIndex);
      ret.add(curr);
    }
    return ret;
  }


  private int prevIndex(int currIndex) {
    return currIndex == 0 ? nodeRing.size() - 1 : currIndex - 1;
  }

  private int nextIndex(int currIndex) {
    return currIndex == nodeRing.size() - 1 ? 0 : currIndex + 1;
  }

  @Override
  public List<Node> route(String storageGroupName, long timestamp) {
    long partitionInstance = timestamp / PARTITION_INTERVAL;
    int hash = Objects.hash(storageGroupName, partitionInstance);
    int socketNum = Math.abs(hash % SOCKET_NUM);
    Node node = socketNodeMap.get(socketNum);

    return getHeaderGroup(node);
  }

  /**
   * When a new node is added, the local node may encounter one of the three changes:
   * 1. the node joins a new group generated by the new node.
   * 2. the new node is added to the node's subordinate group, but the local
   *    node is not the last node and thus still remains.
   * 3. the new node is added to the node's subordinate group, and the local
   *    node is the last node and thus leaves the group.
   * Those three kinds of groups are named newGroups, changedGroups and retireGroups respectively.
   */
  @Override
  public AddNodePartitionUpdate addNode(Node node) {
    nodeRing.add(node);
    nodeRing.sort(Comparator.comparingInt(Node::getNodeIdentifier));

    AddNodePartitionUpdate update = new AddNodePartitionUpdate();
    PartitionGroup newGroup = getHeaderGroup(node);
    if (newGroup.contains(thisNode)) {
      update.newGroup = newGroup;
    }

    for(int i = 0; i < localGroups.size(); i++) {
      PartitionGroup oldGroup = localGroups.get(i);
      Node header = oldGroup.getHeader();
      PartitionGroup newGrp = getHeaderGroup(header);
      if (newGrp.contains(node) && newGrp.contains(thisNode)) {
        // this group changes but still contains the local node
        update.changedGroups.add(newGroup);
        localGroups.set(i, newGroup);
      } else if (newGrp.contains(node) && !newGrp.contains(thisNode)) {
        // the local node retires from the group
        update.retiredGroups.add(newGrp);
      }
    }

    // remove retired groups
    Iterator<PartitionGroup> groupIterator = localGroups.iterator();
    while (groupIterator.hasNext()) {
      PartitionGroup partitionGroup = groupIterator.next();
      for (PartitionGroup retiredGroup : update.retiredGroups) {
        if (retiredGroup.getHeader().equals(partitionGroup.getHeader())) {
          groupIterator.remove();
        }
      }
    }

    return update;
  }

  @Override
  public List<PartitionGroup> getLocalGroups() {
    return localGroups;
  }

  @Override
  public ByteBuffer serialize() {
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream(4096);
    DataOutputStream dataOutputStream = new DataOutputStream(outputStream);

    try {
      dataOutputStream.writeInt(nodeSocketMap.size());
      for (Entry<Node, List<Integer>> entry : nodeSocketMap.entrySet()) {
        SerializeUtils.serialize(entry.getKey(), dataOutputStream);
        SerializeUtils.serialize(entry.getValue(), dataOutputStream);
      }
    } catch (IOException ignored) {
      // not reachable
    }
    return ByteBuffer.wrap(outputStream.toByteArray());
  }

  @Override
  public void deserialize(ByteBuffer buffer) {
    int size = buffer.getInt();
    for (int i = 0; i < size; i++) {
      Node node = new Node();
      List<Integer> sockets = new ArrayList<>();
      SerializeUtils.deserialize(node, buffer);
      SerializeUtils.deserialize(sockets, buffer);
      nodeSocketMap.put(node, sockets);
      for (Integer socket : sockets) {
        socketNodeMap.put(socket, node);
      }
    }
    nodeRing.addAll(nodeSocketMap.keySet());
    nodeRing.sort(Comparator.comparingInt(Node::getNodeIdentifier));

    localGroups = getPartitionGroups(thisNode);
  }

  @Override
  public Collection<Node> getAllNodes() {
    return nodeRing;
  }
}