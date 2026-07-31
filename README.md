<div align="center">

# ⭐ Vega Agent

**A private, on-device AI coding & file agent for Android — bring your own API key.**

[فارسی](#فارسی) · [English](#english)

</div>

---

<a name="فارسی"></a>
## 🇮🇷 فارسی

**Vega Agent** یک اپلیکیشن اندرویدی است که یک ایجنت هوش مصنوعیِ کامل — با دسترسی به فایل‌سیستم گوشی، جست‌وجوی وب و اجرای وظایف چندمرحله‌ای — را مستقیماً روی گوشی شما اجرا می‌کند. هیچ سروری در میان نیست: شما کلید API خودتان را (از OpenAI، Anthropic، Google Gemini یا هر gateway سازگار با OpenAI مثل OpenRouter، Azure، Ollama یا LM Studio) وارد می‌کنید و اپ مستقیماً با آن سرویس صحبت می‌کند.

### ✨ امکانات اصلی

- **چند ارائه‌دهنده، تشخیص خودکار پروتکل** — با OpenAI، Anthropic (Claude) و Google Gemini به‌صورت بومی کار می‌کند؛ همچنین با هر gateway سازگار با OpenAI (OpenRouter، Azure OpenAI، سرورهای محلی مثل Ollama/LM Studio) سازگار است. اپ فقط از روی آدرس Base URL، پروتکل درست را حدس می‌زند.
- **ابزارهای واقعی روی فایل‌سیستم** — خواندن/نوشتن/ویرایش فایل، فهرست پوشه‌ها، جست‌وجوی متن و glob، جابه‌جایی/تغییرنام/حذف، ساخت پوشه، بازکردن و استخراج آرشیو (zip)، و خواندن PDF.
- **جست‌وجو و مرورگر وب** — جست‌وجوی وب (DuckDuckGo/Bing) و واکشی صفحات؛ برای سایت‌هایی با محافظ جاوااسکریپتی (مثل Cloudflare)، یک **«حالت انسانی»** با WebView واقعی صفحه را باز و کوکی‌های عبور را برای درخواست‌های بعدی ذخیره می‌کند.
- **حافظهٔ بلندمدت** — ایجنت می‌تواند نکات مهم را با ابزار `remember`/`recall` بین گفت‌وگوها به خاطر بسپارد.
- **Dynamic Workflow (واگذاری به زیرایجنت‌ها)** — برای کارهای بزرگ، ایجنت اصلی می‌تواند بخش‌هایی از کار را به‌صورت موازی به زیرایجنت‌های مستقل واگذار کند؛ هر زیرایجنت فقط دستور خودش را می‌بیند، نه کل گفت‌وگو را.
- **تأیید کاربر برای هر اقدام** — پیش از هرگونه تغییر در فایل‌ها یا اجرای ابزار، از شما اجازه گرفته می‌شود؛ گزینهٔ «همیشه اجازه بده» هم برای کاهش مزاحمت وجود دارد.
- **اجرا در پس‌زمینه** — وظایف طولانی در یک Foreground Service اجرا می‌شوند و با ترک‌کردن اپ متوقف نمی‌شوند؛ برای گوشی‌های شیائومی/هواوی/اوپو/سامسونگ و... راهنمای غیرفعال‌کردن محدودیت‌های اجرای خودکار سازنده هم داخل اپ هست.
- **ذخیرهٔ امن کلید API** — کلید با AES-256-GCM و کلیدِ سخت‌افزاریِ AndroidKeyStore رمزنگاری می‌شود؛ در صورت نبود keystore سالم روی دستگاه، اپ باز هم کار می‌کند و صادقانه اعلام می‌کند که حفاظت سخت‌افزاری فعال نیست.
- **بررسی پیش از اجرا (Preflight)** — قبل از ارسال هر درخواست، حالت‌های قطعاً ناموفق (کلید نامعتبر، عدم تطابق کلید با سرویس، نبود مدل، آدرس نامعتبر) شناسایی و فوراً اعلام می‌شوند؛ به‌جای دقیقه‌ها انتظار بی‌نتیجه.
- **دوزبانه و کاملاً راست‌به‌چپ** — رابط کاربری فارسی/انگلیسی با تشخیص خودکار جهت متن؛ در حالت فارسی کل چیدمان (کشو، دکمه‌ها، حباب پیام‌ها) آینه می‌شود، اما مسیر فایل، کد، URL و کلید API همیشه چپ‌به‌راست باقی می‌مانند.
- **پشتیبانی از شبکه‌های فیلترشده** — سیاست شبکه‌ای طراحی‌شده تا با DNSهای فیلترکنندهٔ داخلی (که آدرس‌های خصوصی برمی‌گردانند) درست کار کند، بدون مسدود کردن نادرست ارائه‌دهنده‌های معتبر.
- **رندر مارک‌داون کامل** — بلوک‌های کد، جداول، بلوک‌های تفکر مدل (`<think>`) به‌صورت پنل جداگانه، دیف رنگی برای ویرایش فایل‌ها.
- **بدون سرور واسط، بدون تبلیغ، بدون ردیابی** — تمام داده‌ها (تاریخچهٔ چت، حافظه، کلید API) فقط روی گوشی شما ذخیره می‌شوند.

### 🔒 حریم خصوصی و امنیت

- کلید API فقط روی دستگاه شما، رمزنگاری‌شده، ذخیره می‌شود.
- ایجنت هرگز بدون تأیید صریح شما فایلی را تغییر نمی‌دهد یا حذف نمی‌کند.
- درخواست‌های وب مدل، طبق سیاست امنیتی، از دسترسی به آدرس‌های داخلی/خصوصی و سرویس‌های متادیتای ابری (مثل `metadata.google.internal`) محافظت می‌شوند؛ مگر اینکه شما آگاهانه دسترسی به شبکهٔ محلی را فعال کنید.

### 🛠️ ساخت از سورس

پروژه با یک اسکریپت shell بدون وابستگی به Gradle قابل ساخت است (`mkapk.sh`؛ نیاز به JDK 17+‎، Kotlin و Android SDK build-tools دارد)، یا به‌صورت معمول با Android Studio / Gradle (`build.gradle.kts`) باز می‌شود. `runtests.sh` مجموعه تست‌های پروژه (تست‌های رفتاری و تست افتراقی) را بدون نیاز به دستگاه اجرا می‌کند.

```bash
./mkapk.sh          # ساخت APK امضاشده
./runtests.sh        # اجرای تست‌ها
```

### ⚠️ سلب مسئولیت

Vega Agent صرفاً یک کلاینت است؛ هزینهٔ استفاده از API هر ارائه‌دهنده بر عهدهٔ شماست. پیش از اجرای دستورات حذف/ویرایش فایل روی داده‌های مهم، از پشتیبان‌گیری اطمینان حاصل کنید.

---

<a name="english"></a>
## 🇬🇧 English

**Vega Agent** is an Android app that runs a full AI agent — with access to your phone's filesystem, the web, and multi-step task execution — entirely on-device. There's no middleman server: you bring your own API key (OpenAI, Anthropic, Google Gemini, or any OpenAI-compatible gateway such as OpenRouter, Azure, Ollama, or LM Studio), and the app talks directly to that provider.

### ✨ Key Features

- **Multi-provider, auto protocol detection** — works natively with OpenAI, Anthropic (Claude), and Google Gemini, and is compatible with any OpenAI-compatible gateway (OpenRouter, Azure OpenAI, local servers like Ollama/LM Studio). The app infers the right wire protocol from your Base URL.
- **Real filesystem tools** — read/write/edit files, list directories, search text and glob patterns, move/rename/delete, create folders, inspect and extract archive (zip) contents, and read PDFs.
- **Web search & browsing** — web search (DuckDuckGo/Bing) and page fetching; for sites behind a JavaScript challenge (e.g. Cloudflare), a **"human mode"** loads the page in a real WebView and reuses the resulting clearance cookies for later requests.
- **Long-term memory** — the agent can save and recall notes across conversations via `remember`/`recall` tools.
- **Dynamic Workflow (sub-agent delegation)** — for larger tasks, the lead agent can delegate focused sub-tasks to independent sub-agents that run in parallel, each with its own clean context.
- **Approval on every action** — nothing is written, edited, deleted, or fetched without your explicit confirmation, with an "always allow" option to reduce friction.
- **Background execution** — long-running tasks run in a foreground service and survive you leaving the app; in-app guidance helps you disable aggressive background-kill behavior on Xiaomi/Huawei/OPPO/Samsung and other OEMs.
- **Secure API key storage** — keys are encrypted with AES-256-GCM using a hardware-backed AndroidKeyStore key; on devices without a working keystore, the app still works and is transparent that hardware protection isn't active.
- **Preflight checks** — before a single request goes out, certain-to-fail configurations (missing key, key/provider mismatch, missing model, invalid endpoint) are detected and reported instantly instead of after a long silent retry.
- **Bilingual, fully RTL-aware** — Persian/English UI with automatic text direction; in Persian the entire layout (drawer, controls, message bubbles) mirrors, while file paths, code, URLs, and API keys always stay left-to-right.
- **Works on filtered networks** — network policy is designed to tolerate DNS filtering that returns sentinel private addresses, without incorrectly blocking legitimate providers.
- **Full markdown rendering** — code blocks, tables, a separate panel for model "thinking" (`<think>`) blocks, and colored diffs for file edits.
- **No backend, no ads, no tracking** — chat history, memory, and your API key are stored only on your device.

### 🔒 Privacy & Security

- Your API key is stored on-device only, encrypted.
- The agent never modifies or deletes a file without your explicit approval.
- Model-initiated web requests are protected from reaching internal/private addresses and cloud metadata endpoints (e.g. `metadata.google.internal`), unless you deliberately enable local-network access.

### 🛠️ Building from Source

The project builds via a dependency-light shell script (`mkapk.sh`; requires JDK 17+, Kotlin, and Android SDK build-tools) or normally via Android Studio / Gradle (`build.gradle.kts`). `runtests.sh` runs the full test suite (behavioral and differential tests) without needing a device.

```bash
./mkapk.sh          # build a signed APK
./runtests.sh        # run the test suite
```

### ⚠️ Disclaimer

Vega Agent is a client only; API usage costs from your chosen provider are your responsibility. Back up important data before running file delete/edit commands against it.

---

<div align="center">

Made for people who want an AI agent on their own terms — their key, their device, their data.

</div>
