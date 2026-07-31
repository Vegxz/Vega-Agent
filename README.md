<div align="center">

# ⚡ Vega Agent

### ایجنت هوش مصنوعی قدرتمند و قابل‌کنترل برای اندروید

`مدیریت فایل` · `تحقیق در وب` · `ویرایش کد` · `اجرای وظایف چندمرحله‌ای`

همه روی دستگاه خودتان — با کلید API شخصی شما (**BYOK**) و بدون سرور واسط اختصاصی.

<br>

[![Android](https://img.shields.io/badge/Android-6.0%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white&labelColor=1B1F23)](https://www.android.com/)
[![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white&labelColor=1B1F23)](https://kotlinlang.org/)
[![Local First](https://img.shields.io/badge/Local--First-00C853?style=for-the-badge&logo=shieldsdotio&logoColor=white&labelColor=1B1F23)](#fa-security)
[![License Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-2962FF?style=for-the-badge&logo=apache&logoColor=white&labelColor=1B1F23)](LICENSE)

<br>

**[🇮🇷 فارسی](#fa)**  ·  **[🇬🇧 English](#en)**  ·  [📸 Screenshots](#screenshots)  ·  [🗺️ فهرست مطالب](#toc)  ·  [📥 نصب](#fa-install)

</div>

---

<a id="screenshots"></a>

## 📸 تصاویر برنامه

<div align="center">

| 📂 مدیریت فایل | 🛡️ حالت اجرای ایجنت | ⚙️ تنظیمات ارائه‌دهنده |
| :---: | :---: | :---: |
| <img src="screenshots/Screenshot%202.jpg" width="240" alt="انتخاب فایل و پوشه در Vega Agent"> | <img src="screenshots/Screenshot%203.jpg" width="240" alt="انتخاب حالت اجرای ایجنت در Vega Agent"> | <img src="screenshots/Screenshot%206.jpg" width="240" alt="تنظیم ارائه‌دهنده مدل هوش مصنوعی"> |
| انتخاب فایل‌ها و پوشه‌ها برای کار ایجنت | خودکار، برنامه‌ریزی یا تأیید مرحله‌ای | پشتیبانی از چندین ارائه‌دهنده و مدل |

| 🧠 تنظیم استدلال | 🔍 جزئیات اجرا | 🌐 جستجو و مرور وب |
| :---: | :---: | :---: |
| <img src="screenshots/Screenshot%201.jpg" width="240" alt="تنظیم سطح استدلال مدل"> | <img src="screenshots/Screenshot%204.jpg" width="240" alt="نمایش مراحل اجرا و فراخوانی ابزارها"> | <img src="screenshots/Screenshot%205.jpg" width="240" alt="جستجو و مرور وب در Vega Agent"> |
| تنظیم توان پردازش از کم تا حداکثر | مشاهده وضعیت مراحل و فراخوانی ابزارها | تحقیق و دریافت اطلاعات زنده از وب |

</div>

---

<a id="toc"></a>

## 🗺️ فهرست مطالب

| 🇮🇷 فارسی | 🇬🇧 English |
| :--- | :--- |
| [معرفی](#fa) | [Overview](#en) |
| [قابلیت‌های اصلی](#fa-features) | [Core Capabilities](#en-features) |
| [پشتیبانی از ارائه‌دهنده‌ها](#fa-providers) | [AI Providers](#en-providers) |
| [ابزارهای فایل‌سیستم](#fa-files) | [Filesystem Tools](#en-files) |
| [حالت‌های اجرای ایجنت](#fa-modes) | [Execution Modes](#en-modes) |
| [استدلال و گردش کار پویا](#fa-reasoning) | [Reasoning & Workflows](#en-reasoning) |
| [جستجو و مرور وب](#fa-web) | [Web Search & Browsing](#en-web) |
| [امنیت و حریم خصوصی](#fa-security) | [Security & Privacy](#en-security) |
| [اجرای پایدار در پس‌زمینه](#fa-background) | [Background Execution](#en-background) |
| [رابط دوزبانه](#fa-i18n) | [Bilingual Interface](#en-i18n) |
| [نصب](#fa-install) | [Installation](#en-install) |
| [ساخت از سورس](#fa-build) | [Build from Source](#en-build) |
| [نکات مهم](#fa-notes) | [Important Notes](#en-notes) |
| [مشارکت در پروژه](#fa-contrib) | [Contributing](#en-contrib) |
| [مجوز](#fa-license) | [License](#en-license) |

---

<a id="fa"></a>

## 🇮🇷 فارسی

**Vega Agent** یک ایجنت هوش مصنوعی برای اندروید است که امکان کار مستقیم با فایل‌ها، جستجو و مرور وب، ویرایش کد و اجرای وظایف چندمرحله‌ای را فراهم می‌کند.

این برنامه با رویکرد **Local-First** طراحی شده است؛ یعنی رابط کاربری، مدیریت وظایف، ابزارهای فایل‌سیستم و ذخیره‌سازی تنظیمات روی دستگاه اجرا می‌شوند و برای عملکرد اصلی برنامه به سرور واسط اختصاصی Vega Agent نیازی نیست.

برای استفاده از مدل‌های هوش مصنوعی، کافی است کلید API ارائه‌دهنده موردنظر خود را وارد کنید.

<a id="fa-features"></a>

### 🚀 قابلیت‌های اصلی

| | قابلیت | در یک نگاه |
| :---: | :--- | :--- |
| 🤖 | **[چندین ارائه‌دهنده](#fa-providers)** | OpenAI، Claude، Gemini، OpenRouter، Azure، Ollama، LM Studio |
| 📂 | **[ابزارهای فایل‌سیستم](#fa-files)** | خواندن، ویرایش با نمایش تغییرات، جستجو، ZIP و PDF |
| 🛡️ | **[حالت‌های اجرا](#fa-modes)** | Automatic، Planning و Accepting |
| 🧠 | **[استدلال و گردش کار پویا](#fa-reasoning)** | تنظیم توان استدلال و تقسیم وظایف پیچیده |
| 🌐 | **[جستجو و مرور وب](#fa-web)** | DuckDuckGo و Bing، دریافت صفحات، مرور با WebView |
| 🔒 | **[امنیت و حریم خصوصی](#fa-security)** | Android Keystore و رمزنگاری AES-256-GCM |
| ⚡ | **[اجرای پایدار در پس‌زمینه](#fa-background)** | Foreground Service برای وظایف طولانی |
| 🎨 | **[رابط دوزبانه](#fa-i18n)** | فارسی راست‌به‌چپ و انگلیسی چپ‌به‌راست |

<a id="fa-providers"></a>

#### 🤖 پشتیبانی از چندین ارائه‌دهنده

Vega Agent با ارائه‌دهنده‌های زیر و هر سرویس سازگار با OpenAI API کار می‌کند:

`OpenAI` · `Anthropic Claude` · `Google Gemini` · `OpenRouter` · `Microsoft Azure OpenAI` · `Ollama` · `LM Studio`

[![BYOK](https://img.shields.io/badge/BYOK-Bring%20Your%20Own%20Key-2962FF?style=flat-square&labelColor=1B1F23)](#fa-install)
[![OpenAI Compatible](https://img.shields.io/badge/OpenAI%20API-Compatible-10A37F?style=flat-square&logo=openai&logoColor=white&labelColor=1B1F23)](#fa-providers)
[![Self Hosted](https://img.shields.io/badge/Ollama%20%C2%B7%20LM%20Studio-Self%20Hosted-00C853?style=flat-square&labelColor=1B1F23)](#fa-security)

> کافی است `Base URL` و کلید API را وارد کنید؛ برنامه پروتکل مناسب را براساس تنظیمات تشخیص می‌دهد. با **Ollama** و **LM Studio** هم می‌توانید مدل‌ها را روی دستگاه یا شبکه خودتان اجرا کنید.

<a id="fa-files"></a>

#### 📂 ابزارهای واقعی فایل‌سیستم

ایجنت می‌تواند با اجازه کاربر عملیات مختلفی روی فایل‌ها انجام دهد:

| 📄 فایل و پوشه | 🔍 جستجو | 📦 قالب‌های خاص |
| :--- | :--- | :--- |
| خواندن و ایجاد فایل | جستجوی متن در فایل‌ها | کار با فایل‌های ZIP |
| ویرایش فایل با نمایش تغییرات | جستجوی فایل با الگوهای Glob | استخراج و پردازش محتوای PDF |
| ایجاد و مدیریت پوشه‌ها | | ویرایش فایل‌های متنی و کد |

> سطح دسترسی واقعی برنامه به نسخه اندروید، مجوزهای اعطاشده و پوشه انتخاب‌شده توسط کاربر بستگی دارد.

<a id="fa-modes"></a>

#### 🛡️ حالت‌های اجرای ایجنت

برای کنترل میزان استقلال ایجنت، سه حالت اجرا در نظر گرفته شده است:

| حالت | رفتار | مناسب برای |
| :--- | :--- | :--- |
| ⚙️ **Automatic** | اجرای وظایف بدون تأیید مرحله‌به‌مرحله | کارهای روتین روی فایل‌هایی که نسخه پشتیبان دارند |
| 🗺️ **Planning** | بررسی درخواست و ارائه برنامه اجرایی پیش از اعمال تغییرات | وظایف پیچیده و چندمرحله‌ای |
| ✅ **Accepting** | دریافت تأیید کاربر پیش از اجرای اقدامات حساس | تغییر فایل‌های مهم و عملیات حساس |

> انتخاب حالت مناسب به نوع وظیفه و میزان کنترلی که نیاز دارید بستگی دارد.

<a id="fa-reasoning"></a>

#### 🧠 استدلال قابل‌تنظیم و گردش کار پویا

بسته به قابلیت مدل انتخاب‌شده، می‌توانید میزان تلاش استدلال را از سطح پایین تا حداکثر تنظیم کنید.

Vega Agent همچنین می‌تواند:

- وظایف پیچیده را به مراحل کوچک‌تر تقسیم کند
- چند فعالیت مستقل را به‌صورت موازی مدیریت کند
- وضعیت اجرای مراحل را نمایش دهد
- فراخوانی ابزارها و نتیجه هر عملیات را ثبت کند

> قابلیت‌های استدلال و اجرای موازی ممکن است میان مدل‌ها و ارائه‌دهندگان مختلف متفاوت باشند.

<a id="fa-web"></a>

#### 🌐 جستجو و مرور وب

ابزارهای وب برنامه شامل موارد زیر هستند:

- جستجو از طریق DuckDuckGo و Bing
- دریافت و بررسی محتوای صفحات وب
- مرور تعاملی صفحات از طریق WebView
- مدیریت دسترسی به آدرس‌های شبکه محلی
- استفاده از صفحات نیازمند تعامل کاربر، ورود یا تأییدهای مرورگر

> Vega Agent برای دور زدن سازوکارهای امنیتی وب‌سایت‌ها طراحی نشده است و استفاده از قابلیت مرور باید مطابق قوانین و شرایط استفاده هر سرویس انجام شود.

<a id="fa-security"></a>

#### 🔒 امنیت و حریم خصوصی

| لایه | محافظت |
| :--- | :--- |
| 🔑 کلیدهای API | محافظت با استفاده از Android Keystore |
| 🔐 داده‌های ذخیره‌شده | رمزنگاری اطلاعات حساس با AES-256-GCM |
| ✅ پیش از درخواست | بررسی اولیه تنظیمات پیش از شروع درخواست |
| 🚧 شبکه | محدودسازی دسترسی به آدرس‌های داخلی و نقاط پایانی حساس |
| 🛡️ درخواست‌های خروجی | محافظت در برابر برخی سناریوهای SSRF |
| 👤 اقدامات ایجنت | امکان کنترل و تأیید اقدامات پیش از اجرا |

Vega Agent سرور واسط اختصاصی برای پردازش مکالمات شما ندارد. بااین‌حال، هنگام استفاده از مدل‌های ابری، متن درخواست‌ها و اطلاعاتی که برای پردازش انتخاب می‌کنید مستقیماً به ارائه‌دهنده API انتخاب‌شده ارسال می‌شوند.

> برای وظایف کاملاً محلی می‌توانید از سرویس‌هایی مانند **Ollama** یا **LM Studio** در شبکه یا دستگاه سازگار استفاده کنید.

<a id="fa-background"></a>

#### ⚡ اجرای پایدار در پس‌زمینه

Vega Agent از Android Foreground Service برای ادامه وظایف طولانی استفاده می‌کند.

راهنمای داخلی مدیریت باتری برای دستگاه‌های برخی برندها نیز ارائه شده است، از جمله Xiaomi، Samsung، Huawei و OPPO.

> تنظیمات باتری و محدودیت‌های پس‌زمینه ممکن است با توجه به مدل دستگاه و نسخه اندروید متفاوت باشند.

<a id="fa-i18n"></a>

#### 🎨 رابط دوزبانه فارسی و انگلیسی

- رابط راست‌به‌چپ برای زبان فارسی
- رابط چپ‌به‌راست برای زبان انگلیسی
- آینه‌سازی چیدمان در حالت RTL
- حفظ جهت LTR برای کدها، URLها، مسیر فایل‌ها و کلیدهای API

<p align="left"><a href="#toc">⬆️ بازگشت به فهرست مطالب</a></p>

---

<a id="fa-install"></a>

### 📥 نصب

[![Latest Release](https://img.shields.io/github/v/release/Vegxz/Vega-Agent?style=for-the-badge&color=FF6D00&logo=github&logoColor=white&labelColor=1B1F23&label=Latest%20Release)](https://github.com/Vegxz/Vega-Agent/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/Vegxz/Vega-Agent/total?style=for-the-badge&color=00B0FF&logo=cloudsmith&logoColor=white&labelColor=1B1F23&label=Downloads)](https://github.com/Vegxz/Vega-Agent/releases)
[![APK Size](https://img.shields.io/github/languages/top/Vegxz/Vega-Agent?style=for-the-badge&color=7F52FF&logo=kotlin&logoColor=white&labelColor=1B1F23&label=Built%20With)](https://kotlinlang.org/)

آخرین نسخه APK را از بخش [**Releases**](https://github.com/Vegxz/Vega-Agent/releases/latest) مخزن دریافت کنید.

| پیش‌نیاز | جزئیات |
| :--- | :--- |
| نسخه اندروید | 6.0 (API 23) یا بالاتر |
| کلید API | از ارائه‌دهنده دلخواه شما (BYOK) |

مراحل نصب:

1. فایل APK را روی دستگاه اندرویدی باز کنید.
2. در صورت نیاز، اجازه نصب از منبع انتخاب‌شده را فعال کنید.
3. برنامه را نصب کرده و ارائه‌دهنده مدل را در بخش تنظیمات پیکربندی کنید.
4. کلید API و مدل موردنظر خود را وارد کنید.
5. پیش از اجرای وظایف حساس، حالت دسترسی مناسب را انتخاب کنید.

> [!IMPORTANT]
> برای امنیت بیشتر، فایل APK را فقط از صفحه رسمی Releases همین مخزن دریافت کنید.

<a id="fa-build"></a>

### 🛠️ ساخت از سورس

برای ساخت پروژه به JDK، Android SDK و دسترسی به شبکه برای دریافت وابستگی‌های پروژه نیاز دارید.

ابتدا مخزن را دریافت کنید:

```bash
git clone https://github.com/Vegxz/Vega-Agent.git
cd Vega-Agent
```

ساخت APK امضاشده:

```bash
./mkapk.sh
```

اجرای تست‌ها:

```bash
./runtests.sh
```

در صورت نیاز، ابتدا مجوز اجرای اسکریپت‌ها را فعال کنید:

```bash
chmod +x mkapk.sh runtests.sh
```

<a id="fa-notes"></a>

### ⚠️ نکات مهم

| | نکته |
| :---: | :--- |
| 🔑 | مسئولیت نگهداری و محافظت از کلیدهای API بر عهده کاربر است. |
| 💳 | هزینه درخواست‌های API براساس تعرفه ارائه‌دهنده انتخاب‌شده محاسبه می‌شود. |
| 💾 | پیش از اجرای حالت **Automatic** روی فایل‌های مهم، از اطلاعات خود نسخه پشتیبان تهیه کنید. |
| 🧐 | خروجی مدل‌های هوش مصنوعی ممکن است نادرست یا ناقص باشد؛ تغییرات حساس را پیش از تأیید نهایی بررسی کنید. |
| 🔐 | دسترسی به فایل‌ها و شبکه باید فقط در محدوده موردنیاز فعال شود. |
| 📱 | قابلیت‌های برنامه می‌توانند با توجه به مدل، ارائه‌دهنده API و نسخه اندروید متفاوت باشند. |

<a id="fa-contrib"></a>

### 🤝 مشارکت در پروژه

[![Issues](https://img.shields.io/github/issues/Vegxz/Vega-Agent?style=flat-square&color=FF6D00&labelColor=1B1F23&label=Issues)](https://github.com/Vegxz/Vega-Agent/issues)
[![Pull Requests](https://img.shields.io/github/issues-pr/Vegxz/Vega-Agent?style=flat-square&color=2962FF&labelColor=1B1F23&label=Pull%20Requests)](https://github.com/Vegxz/Vega-Agent/pulls)
[![Stars](https://img.shields.io/github/stars/Vegxz/Vega-Agent?style=flat-square&color=FFD600&labelColor=1B1F23&label=Stars)](https://github.com/Vegxz/Vega-Agent/stargazers)
[![Forks](https://img.shields.io/github/forks/Vegxz/Vega-Agent?style=flat-square&color=00C853&labelColor=1B1F23&label=Forks)](https://github.com/Vegxz/Vega-Agent/network/members)

مشارکت‌ها، گزارش خطاها و پیشنهادهای شما ارزشمند هستند.

1. مخزن را Fork کنید.
2. یک Branch جدید بسازید.
3. تغییرات خود را Commit کنید.
4. یک Pull Request با توضیحات کامل ارسال کنید.

برای گزارش مشکل، از بخش **Issues** استفاده کرده و در صورت امکان اطلاعات زیر را وارد کنید:

- نسخه Vega Agent و نسخه اندروید
- مدل دستگاه
- ارائه‌دهنده و مدل هوش مصنوعی
- مراحل بازتولید مشکل
- لاگ یا تصویر مرتبط، بدون اطلاعات حساس

<a id="fa-license"></a>

### 📄 مجوز

[![License Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-2962FF?style=flat-square&logo=apache&logoColor=white&labelColor=1B1F23)](LICENSE)

این پروژه تحت مجوز [Apache License 2.0](LICENSE) منتشر شده است.

<p align="left"><a href="#toc">⬆️ بازگشت به فهرست مطالب</a></p>

---

<a id="en"></a>

## 🇬🇧 English

**Vega Agent** is an AI agent for Android that can work directly with files, research the web, edit code, and execute multi-step tasks.

The application follows a **local-first** architecture: its interface, task orchestration, filesystem tools, and configuration storage run on the device without requiring a dedicated Vega Agent intermediary server.

To use an AI model, provide an API key for your preferred provider.

<a id="en-features"></a>

### 🚀 Core Capabilities

| | Capability | At a glance |
| :---: | :--- | :--- |
| 🤖 | **[Multiple providers](#en-providers)** | OpenAI, Claude, Gemini, OpenRouter, Azure, Ollama, LM Studio |
| 📂 | **[Filesystem tools](#en-files)** | Read, edit with change previews, search, ZIP and PDF |
| 🛡️ | **[Execution modes](#en-modes)** | Automatic, Planning and Accepting |
| 🧠 | **[Reasoning & dynamic workflows](#en-reasoning)** | Adjustable reasoning effort and task decomposition |
| 🌐 | **[Web search & browsing](#en-web)** | DuckDuckGo and Bing, page fetching, WebView browsing |
| 🔒 | **[Security & privacy](#en-security)** | Android Keystore and AES-256-GCM encryption |
| ⚡ | **[Persistent background execution](#en-background)** | Foreground Service for longer-running tasks |
| 🎨 | **[Bilingual interface](#en-i18n)** | Right-to-left Persian and left-to-right English |

<a id="en-providers"></a>

#### 🤖 Multiple AI Providers

Vega Agent works with the following providers and any OpenAI-API-compatible service:

`OpenAI` · `Anthropic Claude` · `Google Gemini` · `OpenRouter` · `Microsoft Azure OpenAI` · `Ollama` · `LM Studio`

[![BYOK](https://img.shields.io/badge/BYOK-Bring%20Your%20Own%20Key-2962FF?style=flat-square&labelColor=1B1F23)](#en-install)
[![OpenAI Compatible](https://img.shields.io/badge/OpenAI%20API-Compatible-10A37F?style=flat-square&logo=openai&logoColor=white&labelColor=1B1F23)](#en-providers)
[![Self Hosted](https://img.shields.io/badge/Ollama%20%C2%B7%20LM%20Studio-Self%20Hosted-00C853?style=flat-square&labelColor=1B1F23)](#en-security)

> Just provide a `Base URL` and an API key — the app determines the appropriate protocol from the configuration. With **Ollama** and **LM Studio** you can also run models on your own device or network.

<a id="en-files"></a>

#### 📂 Real Filesystem Tools

With the permissions granted by the user, the agent can:

| 📄 Files & folders | 🔍 Search | 📦 Special formats |
| :--- | :--- | :--- |
| Read and create files | Search text across files | Work with ZIP archives |
| Edit files with change previews | Find files using Glob patterns | Extract and process PDF content |
| Create and manage folders | | Edit source code and text documents |

> Actual filesystem access depends on the Android version, granted permissions, and the directories selected by the user.

<a id="en-modes"></a>

#### 🛡️ Agent Execution Modes

Three execution modes let you control how much autonomy the agent has:

| Mode | Behavior | Best for |
| :--- | :--- | :--- |
| ⚙️ **Automatic** | Executes tasks without step-by-step confirmation | Routine work on files that are backed up |
| 🗺️ **Planning** | Analyzes the request and prepares an execution plan before applying changes | Complex, multi-step tasks |
| ✅ **Accepting** | Asks for confirmation before performing sensitive actions | Editing important files and sensitive operations |

> Choose the mode that matches the task and your preferred level of control.

<a id="en-reasoning"></a>

#### 🧠 Adjustable Reasoning and Dynamic Workflows

Depending on the selected model, reasoning effort can be adjusted from low to maximum.

Vega Agent can also:

- Break complex tasks into smaller steps
- Coordinate independent operations in parallel
- Display task progress
- Show tool calls and operation results

> Reasoning and parallel-execution capabilities vary across models and providers.

<a id="en-web"></a>

#### 🌐 Web Search and Browsing

Web capabilities include:

- DuckDuckGo and Bing search
- Fetching and analyzing web pages
- Interactive browsing through Android WebView
- Configurable local-network access
- User-assisted interaction with pages that require login or browser confirmation

> Vega Agent is not designed to bypass website security mechanisms. Web features should be used in accordance with each website’s terms and applicable laws.

<a id="en-security"></a>

#### 🔒 Security and Privacy

| Layer | Protection |
| :--- | :--- |
| 🔑 API keys | Protected through Android Keystore |
| 🔐 Stored data | AES-256-GCM encryption for sensitive data |
| ✅ Before each request | Configuration preflight checks |
| 🚧 Network | Restrictions for sensitive internal and metadata endpoints |
| 🛡️ Outbound requests | Protection against selected SSRF scenarios |
| 👤 Agent actions | User-controlled confirmation before execution |

Vega Agent does not operate a dedicated intermediary server for processing your conversations. However, when a cloud model is used, prompts and any selected data required for the task are sent directly to the configured API provider.

> For fully local processing, compatible services such as **Ollama** or **LM Studio** can be used on a supported device or network.

<a id="en-background"></a>

#### ⚡ Persistent Background Execution

Vega Agent uses an Android Foreground Service to support longer-running tasks.

The app also includes battery-optimization guidance for selected manufacturers, including Xiaomi, Samsung, Huawei and OPPO.

> Background behavior may vary depending on the device model, Android version, and manufacturer settings.

<a id="en-i18n"></a>

#### 🎨 Native Persian and English Interface

- Right-to-left layout for Persian
- Left-to-right layout for English
- Mirrored layouts in RTL mode
- Consistent LTR direction for code, URLs, file paths, and API keys

<p align="left"><a href="#toc">⬆️ Back to table of contents</a></p>

---

<a id="en-install"></a>

### 📥 Installation

[![Latest Release](https://img.shields.io/github/v/release/Vegxz/Vega-Agent?style=for-the-badge&color=FF6D00&logo=github&logoColor=white&labelColor=1B1F23&label=Latest%20Release)](https://github.com/Vegxz/Vega-Agent/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/Vegxz/Vega-Agent/total?style=for-the-badge&color=00B0FF&logo=cloudsmith&logoColor=white&labelColor=1B1F23&label=Downloads)](https://github.com/Vegxz/Vega-Agent/releases)
[![Built With](https://img.shields.io/github/languages/top/Vegxz/Vega-Agent?style=for-the-badge&color=7F52FF&logo=kotlin&logoColor=white&labelColor=1B1F23&label=Built%20With)](https://kotlinlang.org/)

Download the latest APK from the repository’s [**Releases**](https://github.com/Vegxz/Vega-Agent/releases/latest) section.

| Requirement | Details |
| :--- | :--- |
| Android version | 6.0 (API 23) or newer |
| API key | From the provider of your choice (BYOK) |

Steps:

1. Open the APK on your Android device.
2. Allow installation from the selected source if Android requests it.
3. Install the app and open the provider settings.
4. Enter your API key and select a model.
5. Choose an appropriate execution mode before running sensitive tasks.

> [!IMPORTANT]
> For better security, download APK files only from the official Releases page of this repository.

<a id="en-build"></a>

### 🛠️ Build from Source

You need a JDK, the Android SDK, and network access to fetch the project dependencies.

Clone the repository:

```bash
git clone https://github.com/Vegxz/Vega-Agent.git
cd Vega-Agent
```

Build a signed APK:

```bash
./mkapk.sh
```

Run the test suite:

```bash
./runtests.sh
```

Make the scripts executable when required:

```bash
chmod +x mkapk.sh runtests.sh
```

<a id="en-notes"></a>

### ⚠️ Important Notes

| | Note |
| :---: | :--- |
| 🔑 | Users are responsible for protecting their API keys. |
| 💳 | API usage costs are determined by the selected provider. |
| 💾 | Back up important files before using **Automatic** mode. |
| 🧐 | AI-generated output can be incorrect or incomplete; review sensitive changes before accepting them. |
| 🔐 | Grant filesystem and network access only when required. |
| 📱 | Available capabilities may vary by model, API provider, and Android version. |

<a id="en-contrib"></a>

### 🤝 Contributing

[![Issues](https://img.shields.io/github/issues/Vegxz/Vega-Agent?style=flat-square&color=FF6D00&labelColor=1B1F23&label=Issues)](https://github.com/Vegxz/Vega-Agent/issues)
[![Pull Requests](https://img.shields.io/github/issues-pr/Vegxz/Vega-Agent?style=flat-square&color=2962FF&labelColor=1B1F23&label=Pull%20Requests)](https://github.com/Vegxz/Vega-Agent/pulls)
[![Stars](https://img.shields.io/github/stars/Vegxz/Vega-Agent?style=flat-square&color=FFD600&labelColor=1B1F23&label=Stars)](https://github.com/Vegxz/Vega-Agent/stargazers)
[![Forks](https://img.shields.io/github/forks/Vegxz/Vega-Agent?style=flat-square&color=00C853&labelColor=1B1F23&label=Forks)](https://github.com/Vegxz/Vega-Agent/network/members)

Bug reports, suggestions, and contributions are welcome.

1. Fork the repository.
2. Create a new branch.
3. Commit your changes.
4. Open a Pull Request with a clear description.

When reporting an issue, consider including:

- Vega Agent version and Android version
- Device model
- AI provider and model
- Steps to reproduce
- Relevant logs or screenshots with sensitive information removed

<a id="en-license"></a>

### 📄 License

[![License Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-2962FF?style=flat-square&logo=apache&logoColor=white&labelColor=1B1F23)](LICENSE)

This project is licensed under the [Apache License 2.0](LICENSE).

<p align="left"><a href="#toc">⬆️ Back to table of contents</a></p>

---

<div align="center">

### ⚡ Local-First · User-Controlled · Provider-Flexible

**Vega Agent** به کاربران حرفه‌ای کنترل بیشتری روی گردش کار هوش مصنوعی، فایل‌ها، ارائه‌دهنده‌ها و نحوه اجرای وظایف می‌دهد.

Vega Agent gives power users greater control over their AI workflows, files, providers, and execution preferences.

<br>

[⬆️ بازگشت به فهرست مطالب](#toc) · [🇮🇷 فارسی](#fa) · [🇬🇧 English](#en)

<br>

Made with ❤️ for users who value control, flexibility, privacy, and performance.

</div>
