import cloudscraper
import json

scraper = cloudscraper.create_scraper()
url = "https://guest.freeanimehentai.net/api/v8/video?id=kunoichi-gakuen-ninpouchou-special-1"
response = scraper.get(url, headers={"Referer": "https://hanime.tv/"})
print("Status Code:", response.status_code)
if response.status_code == 200:
    try:
        data = response.json()
        print(json.dumps(data, indent=2)[:1000])
    except Exception as e:
        print("Failed to parse JSON:", e)
        print("Response text:", response.text[:1000])
else:
    print("Response text:", response.text[:1000])
