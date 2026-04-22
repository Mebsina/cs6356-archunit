#!/bin/bash

##### Java
# 2 Spring Framework
find spring-framework -path "*/java/org/springframework" -type d \
  -exec find {} -mindepth 1 -maxdepth 1 -type d \; \
  | sed 's|.*/springframework/|org.springframework.|' \
  | sort -u > inputs/java/spring-framework.txt      

# 7 Kafka
find kafka -path "*/java/org/apache/kafka" -type d \
  -exec find {} -mindepth 1 -maxdepth 1 -type d \; \
  | sed 's|.*/kafka/|org.apache.kafka.|' \
  | sort -u > inputs/java/kafka.txt

# 3 Zookeeper
find zookeeper -path "*/java/org/apache/zookeeper" -type d \
  -exec find {} -mindepth 1 -maxdepth 1 -type d \; \
  | sed 's|.*/zookeeper/|org.apache.zookeeper.|' \
  | sort -u > inputs/java/zookeeper.txt

##### Go
# 1 Consul
find consul -maxdepth 4 -name "*.go" \
  | sed 's|/[^/]*\.go||' \
  | sort -u \
  | sed 's|^consul|github.com/hashicorp/consul|' > inputs/go/consul.txt

# 8 Istio
find istio -maxdepth 4 -name "*.go" \
  | sed 's|/[^/]*\.go||' \
  | sort -u \
  | sed 's|^istio|istio.io/istio|' > inputs/go/istio.txt

# 5 Kubernetes
find kubernetes -maxdepth 4 -name "*.go" \
  | sed 's|/[^/]*\.go||' \
  | sort -u \
  | sed 's|^kubernetes|k8s.io/kubernetes|' > inputs/go/kubernetes.txt




