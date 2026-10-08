FROM node:24-bookworm-slim@sha256:0e0ff40c39bc087845bfb27465a0df4ea419520094bc35842ff83dd8cbe6f9b6 AS node
FROM python:3.13-slim@sha256:8d9d0b8bcf6506481eae4907c18f5e3e7902e629f5f6d684f9e7c32e85e3ddf0

ENV LANG=C.UTF-8 PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 \
    NODE_PATH=/opt/agenteam/node/node_modules \
    PATH=/opt/agenteam/bin:/usr/local/bin:/usr/bin:/bin

COPY --from=node /usr/local/bin/node /usr/local/bin/node
COPY --from=node /usr/local/lib/node_modules /usr/local/lib/node_modules
RUN ln -s /usr/local/lib/node_modules/npm/bin/npm-cli.js /usr/local/bin/npm \
    && ln -s /usr/local/lib/node_modules/npm/bin/npx-cli.js /usr/local/bin/npx \
    && apt-get update \
    && apt-get install -y --no-install-recommends \
       libreoffice-writer libreoffice-calc libreoffice-impress python3-uno \
       poppler-utils fonts-noto-cjk fonts-liberation fontconfig \
    && rm -rf /var/lib/apt/lists/* \
    && fc-cache -f

COPY requirements.txt requirements.lock /opt/agenteam/
RUN pip install --no-cache-dir -r /opt/agenteam/requirements.lock
COPY package.json package-lock.json /opt/agenteam/node/
RUN cd /opt/agenteam/node && npm ci --omit=dev --ignore-scripts \
    && npm cache clean --force
COPY office/ /opt/agenteam/office/
COPY bin/ /opt/agenteam/bin/
RUN sed -i 's/\r$//' /opt/agenteam/bin/agenteam-office \
    && chmod -R a+rX /opt/agenteam \
    && chmod +x /opt/agenteam/bin/* \
    && python -m pip freeze > /opt/agenteam/python-versions.txt \
    && dpkg-query -W > /opt/agenteam/system-versions.txt

COPY --from=node /usr/local/LICENSE /usr/share/licenses/node/LICENSE

ENV HOME=/tmp XDG_CACHE_HOME=/tmp/.cache OPENBLAS_NUM_THREADS=1 OMP_NUM_THREADS=1 NUMEXPR_NUM_THREADS=1
WORKDIR /workspace
USER 65534:65534
CMD ["sh"]
