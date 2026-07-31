<div align="center">

# ⚡ Vega Agent

### ایجنت هوش مصنوعی قدرتمند و قابل‌کنترل برای اندروید

یک محیط کاری هوشمند برای مدیریت فایل‌ها، تحقیق در وب، ویرایش کد و اجرای وظایف چندمرحله‌ای — با استفاده از کلید API شخصی شما (**BYOK**).

[![Platform](https://img.shields.io/badge/Platform-Android_6.0+-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://www.android.com/)
[![Language](https://img.shields.io/badge/Language-Kotlin-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![Security](https://img.shields.io/badge/API_Keys-Android_Keystore-red?style=for-the-badge&logo=android&logoColor=white)](#-امنیت-و-حریم-خصوصی)
[![Privacy](https://img.shields.io/badge/Architecture-Local--First-00C853?style=for-the-badge)](#-امنیت-و-حریم-خصوصی)
[![License](https://img.shields.io/badge/License-MIT-blue?style=for-the-badge)](LICENSE)

[🇮🇷 فارسی](#-فارسی) · [🇬🇧 English](#-english)

</div>

---

## 📸 تصاویر برنامه

<div align="center">

| 📂 مدیریت فایل | 🛡️ حالت اجرای ایجنت | ⚙️ تنظیمات ارائه‌دهنده |
| :---: | :---: | :---: |
| <img src="screenshots/Screenshot%202.jpg" width="240" alt="انتخاب فایل و پوشه در Vega Agent"> | <img src="screenshots/Screenshot%203.jpg" width="240" alt="انتخاب حالت اجرای ایجنت در Vega Agent"> | <img src="screenshots/Screenshot%206.jpg" width="240" alt="تنظیم ارائه‌دهنده مدل هوش مصنوعی"> |
| انتخاب فایل‌ها و پوشه‌ها | خودکار، برنامه‌ریزی یا تأیید مرحله‌ای | پشتیبانی از چندین ارائه‌دهنده |

| 🧠 تنظیم استدلال | 🔍 جزئیات اجرا | 🌐 جستجو و مرور وب |
| :---: | :---: | :---: |
| <img src="screenshots/Screenshot%201.jpg" width="240" alt="تنظیم سطح استدلال مدل"> | <img src="screenshots/Screenshot%204.jpg" width="240" alt="نمایش مراحل اجرا و فراخوانی ابزارها"> | <img src="screenshots/Screenshot%205.jpg" width="240" alt="جستجو و مرور وب در Vega Agent"> |
| تنظیم توان پردازش مدل | مشاهده وضعیت مراحل و ابزارها | تحقیق و دریافت اطلاعات از وب |

</div>

---

## 🇮🇷 فارسی

**Vega Agent** یک ایجنت هوش مصنوعی برای اندروید است که امکان کار مستقیم با فایل‌ها، جستجو و مرور وب، ویرایش کد و اجرای وظایف چندمرحله‌ای را فراهم می‌کند.

این برنامه با رویکرد **Local-First** طراحی شده است؛ یعنی رابط کاربری، مدیریت وظایف، ابزارهای فایل‌سیستم و ذخیره‌سازی تنظیمات روی دستگاه اجرا می‌شوند و برای عملکرد اصلی برنامه به سرور واسط اختصاصی Vega Agent نیازی نیست.

برای استفاده از مدل‌های هوش مصنوعی، کافی است کلید API ارائه‌دهنده موردنظر خود را وارد کنید.

### 🚀 قابلیت‌های اصلی

#### 🤖 پشتیبانی از چندین ارائه‌دهنده

Vega Agent از ارائه‌دهندگان و سرویس‌های مختلف پشتیبانی می‌کند، از جمله:

- OpenAI
- Anthropic Claude
- Google Gemini
- OpenRouter
- Microsoft Azure OpenAI
- Ollama
- LM Studio
- سرویس‌های سازگار با OpenAI API

در سرویس‌های پشتیبانی‌شده، نوع پروتکل می‌تواند براساس تنظیمات و `Base URL` تشخیص داده شود.

#### 📂 ابزارهای واقعی فایل‌سیستم

ایجنت می‌تواند با اجازه کاربر عملیات مختلفی روی فایل‌ها انجام دهد:

- خواندن و ایجاد فایل
- ویرایش فایل با نمایش تغییرات
- جستجوی متن در فایل‌ها
- جستجوی فایل با الگوهای Glob
- ایجاد و مدیریت پوشه‌ها
- کار با فایل‌های ZIP
- استخراج و پردازش محتوای PDF
- ویرایش فایل‌های متنی و کد

> سطح دسترسی واقعی برنامه به نسخه اندروید، مجوزهای اعطاشده و پوشه انتخاب‌شده توسط کاربر بستگی دارد.

#### 🛡️ حالت‌های اجرای ایجنت

برای کنترل میزان استقلال ایجنت، سه حالت اجرا در نظر گرفته شده است:

- **Automatic** — اجرای وظایف بدون تأیید مرحله‌به‌مرحله
- **Planning** — بررسی درخواست و ارائه برنامه اجرایی پیش از اعمال تغییرات
- **Accepting** — دریافت تأیید کاربر پیش از اجرای اقدامات حساس

انتخاب حالت مناسب به نوع وظیفه و میزان کنترلی که نیاز دارید بستگی دارد.

#### 🧠 استدلال قابل‌تنظیم و گردش کار پویا

بسته به قابلیت مدل انتخاب‌شده، می‌توانید میزان تلاش استدلال را از سطح پایین تا حداکثر تنظیم کنید.

Vega Agent همچنین می‌تواند:

- وظایف پیچیده را به مراحل کوچک‌تر تقسیم کند
- چند فعالیت مستقل را به‌صورت موازی مدیریت کند
- وضعیت اجرای مراحل را نمایش دهد
- فراخوانی ابزارها و نتیجه هر عملیات را ثبت کند

> قابلیت‌های استدلال و اجرای موازی ممکن است میان مدل‌ها و ارائه‌دهندگان مختلف متفاوت باشند.

#### 🌐 جستجو و مرور وب

ابزارهای وب برنامه شامل موارد زیر هستند:

- جستجو از طریق DuckDuckGo و Bing
- دریافت و بررسی محتوای صفحات وب
- مرور تعاملی صفحات از طریق WebView
- مدیریت دسترسی به آدرس‌های شبکه محلی
- استفاده از صفحات نیازمند تعامل کاربر، ورود یا تأییدهای مرورگر

Vega Agent برای دور زدن سازوکارهای امنیتی وب‌سایت‌ها طراحی نشده است و استفاده از قابلیت مرور باید مطابق قوانین و شرایط استفاده هر سرویس انجام شود.

#### 🔒 امنیت و حریم خصوصی

ویژگی‌های امنیتی برنامه عبارت‌اند از:

- محافظت از کلیدهای API با استفاده از Android Keystore
- رمزنگاری اطلاعات حساس ذخیره‌شده با AES-256-GCM
- بررسی اولیه تنظیمات پیش از شروع درخواست
- محدودسازی دسترسی به آدرس‌های داخلی و نقاط پایانی حساس
- محافظت در برابر برخی سناریوهای SSRF
- امکان کنترل اقدامات ایجنت پیش از اجرا

Vega Agent سرور واسط اختصاصی برای پردازش مکالمات شما ندارد. بااین‌حال، هنگام استفاده از مدل‌های ابری، متن درخواست‌ها و اطلاعاتی که برای پردازش انتخاب می‌کنید مستقیماً به ارائه‌دهنده API انتخاب‌شده ارسال می‌شوند.

برای وظایف کاملاً محلی می‌توانید از سرویس‌هایی مانند **Ollama** یا **LM Studio** در شبکه یا دستگاه سازگار استفاده کنید.

#### ⚡ اجرای پایدار در پس‌زمینه

Vega Agent از Android Foreground Service برای ادامه وظایف طولانی استفاده می‌کند.

راهنمای داخلی مدیریت باتری برای دستگاه‌های برخی برندها نیز ارائه شده است، از جمله:

- Xiaomi
- Samsung
- Huawei
- OPPO

تنظیمات باتری و محدودیت‌های پس‌زمینه ممکن است با توجه به مدل دستگاه و نسخه اندروید متفاوت باشند.

#### 🎨 رابط دوزبانه فارسی و انگلیسی

برنامه از دو زبان فارسی و انگلیسی پشتیبانی می‌کند:

- رابط راست‌به‌چپ برای زبان فارسی
- رابط چپ‌به‌راست برای زبان انگلیسی
- آینه‌سازی چیدمان در حالت RTL
- حفظ جهت LTR برای کدها، URLها، مسیر فایل‌ها و کلیدهای API

---

### 📥 نصب

آخرین نسخه APK را از بخش **Releases** مخزن دریافت کنید.

پس از دانلود:

1. فایل APK را روی دستگاه اندرویدی باز کنید.
2. در صورت نیاز، اجازه نصب از منبع انتخاب‌شده را فعال کنید.
3. برنامه را نصب کرده و ارائه‌دهنده مدل را در بخش تنظیمات پیکربندی کنید.
4. کلید API و مدل موردنظر خود را وارد کنید.
5. پیش از اجرای وظایف حساس، حالت دسترسی مناسب را انتخاب کنید.

> برای امنیت بیشتر، فایل APK را فقط از صفحه رسمی Releases همین مخزن دریافت کنید.

---

### 🛠️ ساخت از سورس

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

---

### ⚠️ نکات مهم

- مسئولیت نگهداری و محافظت از کلیدهای API بر عهده کاربر است.
- هزینه درخواست‌های API براساس تعرفه ارائه‌دهنده انتخاب‌شده محاسبه می‌شود.
- پیش از اجرای حالت **Automatic** روی فایل‌های مهم، از اطلاعات خود نسخه پشتیبان تهیه کنید.
- خروجی مدل‌های هوش مصنوعی ممکن است نادرست یا ناقص باشد؛ تغییرات حساس را پیش از تأیید نهایی بررسی کنید.
- دسترسی به فایل‌ها و شبکه باید فقط در محدوده موردنیاز فعال شود.
- قابلیت‌های برنامه می‌توانند با توجه به مدل، ارائه‌دهنده API و نسخه اندروید متفاوت باشند.

---

### 🤝 مشارکت در پروژه

مشارکت‌ها، گزارش خطاها و پیشنهادهای شما ارزشمند هستند.

برای مشارکت می‌توانید:

1. مخزن را Fork کنید.
2. یک Branch جدید بسازید.
3. تغییرات خود را Commit کنید.
4. یک Pull Request با توضیحات کامل ارسال کنید.

برای گزارش مشکل، از بخش **Issues** استفاده کرده و در صورت امکان اطلاعات زیر را وارد کنید:

- نسخه Vega Agent
- نسخه اندروید
- مدل دستگاه
- ارائه‌دهنده و مدل هوش مصنوعی
- مراحل بازتولید مشکل
- لاگ یا تصویر مرتبط، بدون اطلاعات حساس

---

### 📄 مجوز

این پروژه تحت مجوز [MIT](LICENSE) منتشر شده است.

---

## 🇬🇧 English

**Vega Agent** is an AI agent for Android that can work directly with files, research the web, edit code, and execute multi-step tasks.

The application follows a **local-first** architecture: its interface, task orchestration, filesystem tools, and configuration storage run on the device without requiring a dedicated Vega Agent intermediary server.

To use an AI model, provide an API key for your preferred provider.

### 🚀 Core Capabilities

#### 🤖 Multiple AI Providers

Vega Agent supports several providers and compatible services, including:

- OpenAI
- Anthropic Claude
- Google Gemini
- OpenRouter
- Microsoft Azure OpenAI
- Ollama
- LM Studio
- OpenAI-compatible APIs

For supported services, the application can determine the appropriate protocol from the configuration and `Base URL`.

#### 📂 Filesystem Tools

With the permissions granted by the user, the agent can:

- Read and create files
- Edit files with change previews
- Search text across files
- Find files using Glob patterns
- Create and manage folders
- Work with ZIP archives
- Extract and process PDF content
- Edit source code and text documents

> Actual filesystem access depends on the Android version, granted permissions, and the directories selected by the user.

#### 🛡️ Agent Execution Modes

Vega Agent provides three execution modes:

- **Automatic** — executes tasks without step-by-step confirmation
- **Planning** — analyzes the request and prepares an execution plan before applying changes
- **Accepting** — asks for confirmation before performing sensitive actions

Choose the mode that matches the task and your preferred level of control.

#### 🧠 Adjustable Reasoning and Dynamic Workflows

Depending on the selected model, reasoning effort can be adjusted from low to maximum.

Vega Agent can also:

- Break complex tasks into smaller steps
- Coordinate independent operations in parallel
- Display task progress
- Show tool calls and operation results

> Reasoning and parallel-execution capabilities vary across models and providers.

#### 🌐 Web Search and Browsing

Web capabilities include:

- DuckDuckGo and Bing search
- Fetching and analyzing web pages
- Interactive browsing through Android WebView
- Configurable local-network access
- User-assisted interaction with pages that require login or browser confirmation

Vega Agent is not designed to bypass website security mechanisms. Web features should be used in accordance with each website’s terms and applicable laws.

#### 🔒 Security and Privacy

Security features include:

- API-key protection through Android Keystore
- AES-256-GCM encryption for stored sensitive data
- Configuration preflight checks
- Restrictions for sensitive internal and metadata endpoints
- Protection against selected SSRF scenarios
- User-controlled confirmation before agent actions

Vega Agent does not operate a dedicated intermediary server for processing your conversations. However, when a cloud model is used, prompts and any selected data required for the task are sent directly to the configured API provider.

For local processing, compatible services such as **Ollama** or **LM Studio** can be used on a supported device or network.

#### ⚡ Background Execution

Vega Agent uses an Android Foreground Service to support longer-running tasks.

The app also includes battery-optimization guidance for selected manufacturers, including:

- Xiaomi
- Samsung
- Huawei
- OPPO

Background behavior may vary depending on the device model, Android version, and manufacturer settings.

#### 🎨 Native Persian and English Interface

The application provides:

- Right-to-left layout for Persian
- Left-to-right layout for English
- Mirrored layouts in RTL mode
- Consistent LTR direction for code, URLs, file paths, and API keys

---

### 📥 Installation

Download the latest APK from the repository’s **Releases** section.

After downloading:

1. Open the APK on your Android device.
2. Allow installation from the selected source if Android requests it.
3. Install the app and open the provider settings.
4. Enter your API key and select a model.
5. Choose an appropriate execution mode before running sensitive tasks.

> For better security, download APK files only from the official Releases page of this repository.

---

### 🛠️ Build from Source

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

---

### ⚠️ Important Notes

- Users are responsible for protecting their API keys.
- API usage costs are determined by the selected provider.
- Back up important files before using **Automatic** mode.
- AI-generated output can be incorrect or incomplete; review sensitive changes before accepting them.
- Grant filesystem and network access only when required.
- Available capabilities may vary by model, API provider, and Android version.

---

### 🤝 Contributing

Bug reports, suggestions, and contributions are welcome.

To contribute:

1. Fork the repository.
2. Create a new branch.
3. Commit your changes.
4. Open a Pull Request with a clear description.

When reporting an issue, consider including:

- Vega Agent version
- Android version
- Device model
- AI provider and model
- Steps to reproduce
- Relevant logs or screenshots with sensitive information removed

---

### 📄 License

This project is available under the [MIT License](LICENSE).

---

<div align="center">

### ⚡ Local-First. User-Controlled. Provider-Flexible.

Vega Agent gives power users greater control over their AI workflows, files, providers, and execution preferences.

Made with ❤️ for users who value control, flexibility, privacy, and performance.

</div>
