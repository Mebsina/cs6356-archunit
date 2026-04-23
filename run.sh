#!/bin/bash

# ArchEval Benchmark Repositories
# git clone https://github.com/panrusheng/arch-eval-benchmark.git

clone_java() {
    echo "Cloning Java repositories..."

    # 2 Spring Framework: Java(8986), Kotlin(328), YAML(25)
    git clone https://github.com/spring-projects/spring-framework.git

    # 3 Zookeeper: Java(950), C/C++(59), Python(36)
    git clone https://github.com/apache/zookeeper.git

    # 7 Kafka: Java(5549), Python(178), YAML(65)
    git clone https://github.com/apache/kafka.git
}

clone_go() {
    echo "Cloning Go repositories..."

    # 1 Consul: Go(2361), JS/TS(1164), YAML(78)
    git clone https://github.com/hashicorp/consul.git

    # 5 Kubernetes: Go(15941), YAML(5225), Markdown(562)
    git clone https://github.com/kubernetes/kubernetes.git

    # 8 Istio: YAML(2595), Go(1886), Markdown(87)
    git clone https://github.com/istio/istio.git
}

echo "Select repositories to clone:"
echo "1) All"
echo "2) Java"
echo "3) Go"
read -p "Enter option (1-3): " option

case $option in
    1)
        clone_java
        clone_go
        ;;
    2)
        clone_java
        ;;
    3)
        clone_go
        ;;
    *)
        echo "Invalid option selected."
        exit 1
        ;;
esac