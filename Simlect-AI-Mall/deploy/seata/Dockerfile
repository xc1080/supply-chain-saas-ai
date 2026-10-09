FROM apache/seata-server:2.2.0

# 官方镜像 classpath 为 /seata-server/libs/*（见 jib-classpath-file）
# validate() 用 TCCL 加载驱动，必须放进 libs；/lib/jdbc 仅与 -Dloader.path 错误提示一致
COPY jdbc/mysql-connector-j-8.0.33.jar /seata-server/libs/mysql-connector-j-8.0.33.jar
COPY jdbc/mysql-connector-j-8.0.33.jar /lib/jdbc/mysql-connector-j-8.0.33.jar

COPY application.yml /seata-server/resources/application.yml
