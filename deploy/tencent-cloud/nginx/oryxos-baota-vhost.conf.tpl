# 宝塔面板 Nginx 反向代理参考片段（适用于已备案域名子域名，如 oryxos.curlyhair.cn）
# 使用说明：
# 1. 在宝塔面板 -> 网站 -> 添加站点：绑定域名 oryxos.curlyhair.cn（纯静态），申请并开启强制 HTTPS（TLS 1.2/1.3）。
# 2. 在宝塔面板 -> 网站设置 -> 访问限制（密码访问）开启 Basic Auth，或使用 htpasswd 生成：
#      htpasswd -c /www/server/panel/vhost/htpasswd/oryxos.htpasswd admin
#    并确保权限：chmod 0640 /www/server/panel/vhost/htpasswd/oryxos.htpasswd
# 3. 确认腾讯云安全组与服务器防火墙（firewalld/ufw）严禁放行 8080 端口（OryxOS 仅监听 127.0.0.1:8080）。
# 4. 在宝塔 nginx.conf 的 http {} 段添加限流区（如尚未配置）：
#      limit_req_zone $binary_remote_addr zone=oryxos_limit:10m rate=10r/s;

# 禁止公网暴露 OpenAPI / Swagger 文档接口
location ~ ^/(swagger-ui|v3/api-docs) {
    return 403;
}

# 全站（含 /admin/ 与 /api/v1/**）强制 Basic Auth + 请求限流 + 反代至本机 127.0.0.1:8080
location / {
    auth_basic "OryxOS Admin Restricted";
    auth_basic_user_file /www/server/panel/vhost/htpasswd/oryxos.htpasswd;

    limit_req zone=oryxos_limit burst=20 nodelay;

    proxy_pass http://127.0.0.1:8080;
    proxy_http_version 1.1;

    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_set_header Connection "";

    # 匹配长推理响应与 application.yml keep-alive 超时
    proxy_connect_timeout 10s;
    proxy_send_timeout 120s;
    proxy_read_timeout 120s;
}
