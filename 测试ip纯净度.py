import requests
from concurrent.futures import ThreadPoolExecutor, as_completed
import time
from pathlib import Path

TARGET_URL = "https://v.wjx.cn/vm/Qs11Kip.aspx#"
TIMEOUT = 8
MAX_WORKERS = 20   # 同时测试20个IP，速度快

def test_proxy(proxy_line):
    proxy_line = proxy_line.strip()
    if not proxy_line:
        return None
    
    proxy = {
        "http": f"http://{proxy_line}",
        "https": f"http://{proxy_line}"
    }
    
    try:
        start = time.time()
        resp = requests.get(TARGET_URL, proxies=proxy, timeout=TIMEOUT, headers={
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
        })
        if resp.status_code == 200 and "问卷" in resp.text:
            print(f"✅ 可用: {proxy_line} ({time.time()-start:.1f}s)")
            return proxy_line
    except:
        pass
    print(f"❌ 不可用: {proxy_line}")
    return None

if __name__ == "__main__":
    base_dir = Path(__file__).resolve().parent

    with open(base_dir / "proxies.txt", "r", encoding="utf-8") as f:
        proxies = [line.strip() for line in f if line.strip()]
    
    print(f"开始测试 {len(proxies)} 个代理...\n")
    
    good_proxies = []
    with ThreadPoolExecutor(max_workers=MAX_WORKERS) as executor:
        futures = {executor.submit(test_proxy, p): p for p in proxies}
        for future in as_completed(futures):
            result = future.result()
            if result:
                good_proxies.append(result)
    
    with open(base_dir / "good_proxies.txt", "w", encoding="utf-8") as f:
        f.write("\n".join(good_proxies))
    
    print(f"\n✅ 测试完成！可用IP数量: {len(good_proxies)}")
    print("已生成 good_proxies.txt，可直接替换原来的 proxies.txt")
