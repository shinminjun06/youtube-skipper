import tkinter as tk
from selenium import webdriver
from selenium.webdriver.common.by import By
from selenium.webdriver.chrome.options import Options
import threading
import time

class YoutubeSkipperApp:
    def __init__(self, root):
        self.root = root
        self.root.title("YouTube Ad Skipper")
        self.root.geometry("300x150")
        self.root.attributes("-topmost", True) # 항상 위 표시
        
        self.label = tk.Label(root, text="광고 감시 중...", font=("Arial", 12), fg="green")
        self.label.pack(pady=10)
        
        self.count = 0
        self.count_label = tk.Label(root, text=f"넘긴 광고 수: {self.count}", font=("Arial", 10))
        self.count_label.pack(pady=5)
        
        # 별도 스레드에서 광고 감시 시작
        self.monitor_thread = threading.Thread(target=self.monitor_ads, daemon=True)
        self.monitor_thread.start()

    def monitor_ads(self):
        # 현재 열려있는 크롬 브라우저에 연결하기 위한 설정
        chrome_options = Options()
        chrome_options.add_experimental_option("debuggerAddress", "127.0.0.1:9222")
        
        try:
            driver = webdriver.Chrome(options=chrome_options)
            while True:
                try:
                    # 유튜브 광고 건너뛰기 버튼의 클래스 이름을 직접 찾습니다.
                    skip_button = driver.find_elements(By.CLASS_NAME, "ytp-ad-skip-button")
                    if skip_button:
                        skip_button[0].click()
                        self.count += 1
                        self.count_label.config(text=f"넘긴 광고 수: {self.count}")
                except:
                    pass
                time.sleep(1)
        except Exception as e:
            self.label.config(text="크롬 연결 실패 (디버깅 모드 필요)", fg="red")

if __name__ == "__main__":
    root = tk.Tk()
    app = YoutubeSkipperApp(root)
    root.mainloop()
