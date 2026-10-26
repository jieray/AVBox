#coding=utf-8
#!/usr/bin/python
import os
import requests
from importlib.util import spec_from_file_location, module_from_spec
from urllib import parse
import json
import sys
import crypto_protocol_dh
sys.dont_write_bytecode = True

PLUGIN_DOWNLOAD_TIMEOUT = 20

def setSiteProxy(url):
    # Java 侧 SiteProxy 同步当前站点代理地址;空串表示关闭
    try:
        from base.localProxy import setSiteProxy as _set
        return _set(url)
    except Exception as e:
        print('setSiteProxy failed: ' + str(e))
        return ''


def createFile(file_path):
    if os.path.exists(file_path) is False:
        os.makedirs(file_path)

def redirectResponse(tUrl, maxRedirects=5):
  # BugReview P3:重定向跟随加次数上限,防循环重定向触发 RecursionError
  rsp = requests.get(tUrl, allow_redirects=False, verify=False, timeout=PLUGIN_DOWNLOAD_TIMEOUT)
  if 'Location' in rsp.headers and maxRedirects > 0:
    return redirectResponse(rsp.headers['Location'], maxRedirects - 1)
  else:
    return rsp

def downloadFile(name,url):
    try:
        rsp = redirectResponse(url)
        with open(name,'wb') as f:
            f.write(rsp.content)
        print(url)
    except:
        print(name + ' =======================================> error')
        print(url)

def downloadPlugin(basePath,url):
    createFile(basePath)
    name = url.split('/')[-1].split('.')[0]
    pyName = ''
    if url.startswith('file://'):
        pyName = url.replace('file://','')
    else:
        pyName = basePath + name+'.py'
        downloadFile(pyName,url)
    sPath = gParam['SpiderPath']
    sPath[name] = pyName
    sParam = gParam['SpiderParam']
    paramList = parse.parse_qs(parse.urlparse(url).query).get('extend')
    if paramList == None:
        paramList = ['']
    sParam[name] = paramList[0]
    return pyName

def registerPluginAlias(alias,fileName):
    if alias == None or alias == '':
        return
    name = fileName.split('/')[-1].split('.')[0]
    sPath = gParam['SpiderPath']
    sPath[alias] = fileName
    sParam = gParam['SpiderParam']
    sParam[alias] = sParam[name] if name in sParam.keys() else ''

def loadFromDisk(fileName):
    name = fileName.split('/')[-1].split('.')[0]
    spList = gParam['SpiderList']
    # 2026-09-12 改用 PEP 451 加载。原 SourceFileLoader(...).load_module() 有两个问题:
    #   ① 该 API 在 CPython 3.12 已被移除(Chaquopy 可用 3.10~3.14,一旦升版本 Python 源会全线加载失败);
    #   ② 它会复用 sys.modules 里同一个 module 字典并重执行源码 —— 同文件被多源引用时模块级全局状态互相覆盖。
    # 现在每次加载都得到全新的 module 对象(命名空间隔离),并登记进 sys.modules 以保留 `import <名字>` 语义。
    spec = spec_from_file_location(name, fileName)
    module = module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    sp = module.Spider()
    spList[name] = sp
    return spList[name]

def str2json(content):
    return json.loads(content)

def getDependenceList(ru):
    get_dependence = getattr(ru, 'getDependence', None)
    if callable(get_dependence):
        result = get_dependence()
        return result if result is not None else []
    return []

def setExtendInfo(ru, extend):
    setter = getattr(ru, 'setExtendInfo', None)
    if callable(setter):
        setter(extend)
    else:
        setattr(ru, 'extend', extend)

gParam = {
    "SpiderList":{},
    "SpiderPath":{},
    "SpiderParam":{}
}

def getDependence(ru):
    return getDependenceList(ru)

def getName(ru):
    result = ru.getName()
    return result

def init(ru,extend):
    spoList = []
    spList = gParam['SpiderList']
    sPath = gParam['SpiderPath']
    sParam = gParam['SpiderParam']
    for key in getDependenceList(ru):
        sp = None
        if key in spList.keys():
            sp = spList[key]
        elif key in sPath.keys():
            sp = loadFromDisk(sPath[key])
        if sp != None:
            setExtendInfo(sp, sParam[key])
            spoList.append(sp)
    setExtendInfo(ru, extend)
    ru.init(spoList if len(spoList) > 0 else extend)

def homeContent(ru,filter):
    result = ru.homeContent(filter)
    formatJo = json.dumps(result,ensure_ascii=False)
    return formatJo

def homeVideoContent(ru):
    result = ru.homeVideoContent()
    formatJo = json.dumps(result,ensure_ascii=False)
    return formatJo

def categoryContent(ru,tid, pg, filter, extend):
    result = ru.categoryContent(tid, pg, filter, str2json(extend))
    formatJo = json.dumps(result,ensure_ascii=False)
    return formatJo

def detailContent(ru,array):
    result = ru.detailContent(str2json(array))
    formatJo = json.dumps(result,ensure_ascii=False)
    return formatJo

def playerContent(ru,flag,id,vipFlags):
    result = ru.playerContent(flag,id,str2json(vipFlags))
    formatJo = json.dumps(result,ensure_ascii=False)
    return formatJo

def liveContent(ru,url):
    result = ru.liveContent(url)
    return result

def searchContent(ru,key,quick):
    result = ru.searchContent(key,quick)
    formatJo = json.dumps(result,ensure_ascii=False)
    return formatJo

def localProxy(ru,param):
    result = ru.localProxy(str2json(param))
    return result

def destroy(ru):
    ru.destroy()

def run():
    pass

if __name__ == '__main__':
    run()
