

# jenkins部署应用到k8s集群


### namespace

    kubectl create namespace oss-app

### 部署步骤

1. 部署ConfigMap到集群

        kubectl create configmap -f common.yaml
        kubectl create configmap -f peer1.yaml
        kubectl create configmap my-config --from-file=key1=/path/to/bar/file1.txt

2. 部署Service到集群

        # service defined all eureka node
        kubectl create -f k8s-service.yaml

3. 部署应用到集群

        sed "s/#{PEER_NAME}#/peer1/g" oss-eureka/k8s-deployment.yaml.template > k8s-deployment-peer1.yaml
        kubectl create -f k8s-deployment-peer1.yaml
