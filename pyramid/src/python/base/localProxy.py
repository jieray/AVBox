from com.github.catvod import Proxy as CatVodProxy

class Proxy:
    def getUrl(self, local):
        return CatVodProxy.getUrl(local).replace('/proxy', '')

    def getPort(self):
        return CatVodProxy.getPort()

# 站点级代理:由 Java 侧 SiteProxy.syncPython() 调用 setSiteProxy 写入
# py 脚本用 requests 自行发请求,不经过 OkHttp,故只能在这里单独同步
_SITE_PROXY = ''


def setSiteProxy(url):
    global _SITE_PROXY
    _SITE_PROXY = (url or '').strip()
    try:
        import os
        if _SITE_PROXY:
            os.environ['HTTP_PROXY'] = _SITE_PROXY
            os.environ['HTTPS_PROXY'] = _SITE_PROXY
            os.environ['http_proxy'] = _SITE_PROXY
            os.environ['https_proxy'] = _SITE_PROXY
        else:
            for k in ('HTTP_PROXY', 'HTTPS_PROXY', 'http_proxy', 'https_proxy'):
                os.environ.pop(k, None)
    except Exception:
        pass
    return _SITE_PROXY


def getSiteProxy():
    return _SITE_PROXY


def proxies():
    if not _SITE_PROXY:
        return None
    return {'http': _SITE_PROXY, 'https': _SITE_PROXY}
