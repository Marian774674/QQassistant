# 主动发送桥：Spring Boot 调它，它再调 LangBot / NapCat
# 构建上下文 = QQMessageAssistantPython（见 deploy/docker-compose.yml）
# 拉不动 Docker Hub 时换基础镜像，例如：
#   docker compose build --build-arg PYTHON_IMAGE=docker.m.daocloud.io/library/python:3.12-slim send_bridge
ARG PYTHON_IMAGE=python:3.12-slim
FROM ${PYTHON_IMAGE}

ENV PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PYTHONDONTWRITEBYTECODE=1

WORKDIR /app

COPY send_bridge/requirements.txt send_bridge/requirements.txt
RUN pip install -r send_bridge/requirements.txt

COPY send_bridge/*.py send_bridge/

EXPOSE 5301
CMD ["python", "-m", "send_bridge"]
