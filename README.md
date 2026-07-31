<div align="center">

# ⚡ Vega Agent

### **ایجنت هوش مصنوعی حرفه‌ای برای کدنویسی و مدیریت فایل، به‌صورت کاملاً لوکال روی اندروید**
*یک فضای کاری هوشمند، خصوصی، قدرتمند و کاملاً قابل‌تنظیم در جیب شما — با کلید API اختصاصی خودتان (BYOK).*

[![Platform](https://img.shields.io/badge/Platform-Android_8.0+-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://android.com)
[![Language](https://img.shields.io/badge/Language-Kotlin-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Security](https://img.shields.io/badge/Security-AES--256--GCM-red?style=for-the-badge&logo=google-fit&logoColor=white)](#-امنیت-و-حریم-خصوصی)
[![Privacy](https://img.shields.io/badge/Privacy-100%25_Local-00C853?style=for-the-badge)](#-حریم-خصوصی)
[![License](https://img.shields.io/badge/License-MIT-blue?style=for-the-badge)](LICENSE)

[🇮🇷 **راهنمای فارسی**](#-فارسی) &nbsp;|&nbsp; [🇬🇧 **English Guide**](#-english)

</div>

---

## 📸 گالری تصاویر / Screenshots

<div align="center">

| 📂 مدیریت فایل و فضای کار | 🛡️ سطح دسترسی و تأیید | ⚙️ تنظیمات ارائه‌دهنده |
| :---: | :---: | :---: |
| <img src="Screenshots/Screenshot 2.jpg" width="260" alt="File Picker"> | <img src="Screenshots/Screenshot 3.jpg" width="260" alt="Assistant Mode"> | <img src="Screenshots/Screenshot 6.jpg" width="260" alt="Settings"> |
| **انتخاب مستقیم فایل‌ها و پوشه‌ها** | **حالت‌های خودکار، برنامه‌ریزی و تأیید** | **پشتیبانی از انواع API و پروکسی** |

<br/>

| 🧠 کنترل استدلال و ابزارها | 🔍 شفافیت در استدلال و اجرا | 🌐 نتایج جستجوی وب |
| :---: | :---: | :---: |
| <img src="Screenshots/Screenshot 1.jpg" width="260" alt="Reasoning Settings"> | <img src="Screenshots/Screenshot 4.jpg" width="260" alt="Thought Trace"> | <img src="Screenshots/Screenshot 5.jpg" width="260" alt="Web Search Results"> |
| **تنظیم قدرت استدلال و Dynamic Workflow** | **نمایش مراحل فکر کردن و فراخوانی ابزارها** | **جستجو و مرور هوشمند وب** |

</div>

---

<a name="فارسی"></a>
## 🇮🇷 فارسی

**Vega Agent** یک ایجنت هوش مصنوعی پیشرفته، مستقل و کاملاً **لوکال** برای سیستم‌عامل اندروید است. این برنامه دسترسی کامل به فایل‌سیستم دستگاه، ابزارهای جست‌وجوی وب، اجرای کارهای چندمرحله‌ای و ویرایش کد را به‌صورت مستقیم و بدون نیاز به هیچ سرور واسطی در اختیار شما قرار می‌دهد.

کافی‌ست کلید API اختصاصی خود را (از OpenAI، Anthropic Claude، Google Gemini یا انواع گیت‌وی‌های سازگار مانند OpenRouter، Azure، Ollama و LM Studio) وارد کنید تا اپلیکیشن به‌طور مستقیم و لوکال با سرویس مربوطه ارتباط برقرار کند — بدون واسطه، بدون سرور میانی، و با کنترل کامل شما بر داده‌ها.

---

### ✨ امکانات و ویژگی‌های برجسته

#### 🤖 ۱. پشتیبانی از چند ارائه‌دهنده و تشخیص هوشمند پروتکل
- پشتیبانی بومی از **OpenAI**، **Anthropic (Claude)** و **Google Gemini**.
- سازگاری کامل با تمامی سرورها و گیت‌وی‌های سازگار با OpenAI (مانند OpenRouter، Azure OpenAI، Ollama، LM Studio).
- **تشخیص خودکار پروتکل:** تعیین پروتکل ارتباطی مناسب به‌صورت هوشمند، تنها از روی Base URL.

#### 📂 ۲. ابزارهای واقعی مدیریت فایل‌سیستم
- خواندن، نوشتن و ویرایش هوشمند فایل‌ها همراه با نمایش Diff رنگی.
- جست‌وجوی دقیق متن و الگوهای Glob در فایل‌ها.
- ساخت پوشه، جابه‌جایی، تغییر نام و حذف امن.
- مشاهده و استخراج فایل‌های فشرده (ZIP) و مطالعه مستقیم فایل‌های PDF.

#### 🛡️ ۳. مدیریت سطح دسترسی و حالت‌های ایجنت (Assistant Modes)
- **Automatic (خودکار):** اجرای کاملاً مستقل وظایف و ویرایش آزادانه فایل‌ها.
- **Planning (برنامه‌ریزی):** حالت فقط‌خواندنی؛ ارائه نقشه راه و درخواست تأیید پیش از هر اقدام.
- **Accepting (تأیید گام‌به‌گام):** دریافت اجازه از کاربر پیش از انجام هرگونه تغییر یا فراخوانی ابزار.

#### 🧠 ۴. سیستم استدلال پیشرفته و Dynamic Workflow
- **Reasoning Effort Control:** قابلیت تنظیم میزان قدرت استدلال مدل (از Low تا Maximum).
- **Dynamic Workflow (واگذاری به زیرایجنت‌ها):** شکستن کارهای پیچیده به فازهای مجزا و واگذاری آن‌ها به زیرایجنت‌های موازی با حافظه پاک و متمرکز.
- **Thought & Execution Trace:** نمایش شفاف و جداگانه مراحل تفکر مدل (`<think>`)، فراخوانی ابزارها و خطایابی لحظه‌ای.

#### 🌐 ۵. جست‌وجوی وب و مرورگر هوشمند
- جست‌وجوی مستقیم وب از طریق DuckDuckGo و Bing.
- **حالت انسانی (Human Mode):** عبور از چالش‌های جاوااسکریپتی و Cloudflare با استفاده از WebView واقعی و ذخیره کوکی‌های دسترسی.
- کنترل دسترسی به شبکه محلی (Local Network Access).

#### 🔒 ۶. امنیت سخت‌افزاری و حفظ کامل حریم خصوصی
- ذخیره کلید API با استفاده از رمزنگاری **AES-256-GCM** و کلیدهای سخت‌افزاری **AndroidKeyStore**.
- شفافیت کامل در صورت عدم وجود ماژول امنیت سخت‌افزاری در دستگاه.
- **محافظت شبکه (Preflight & Security):** مسدودسازی خودکار دسترسی ایجنت به آدرس‌های حساس داخلی و متادیتای ابری (مانند `metadata.google.internal`).
- **بررسی Preflight:** تشخیص سریع خطاهای تنظیمات (کلید نامعتبر، عدم تطابق مدل و آدرس) پیش از ارسال درخواست اصلی.

#### ⚡ ۷. اجرای پایدار در پس‌زمینه
- بهره‌گیری از **Foreground Service** جهت ادامه اجرای پردازش‌های سنگین حتی هنگام خروج از اپلیکیشن.
- راهنمای اختصاصی درون‌برنامه‌ای برای مدیریت محدودیت‌های باتری در رابط‌های کاربری شیائومی، سامسونگ، هواوی و اوپو.

#### 🎨 ۸. رابط کاربری دوزبانه و مدرن (RTL/LTR)
- پشتیبانی کامل و نیتیو از زبان‌های فارسی و انگلیسی.
- آینه‌سازی کامل چیدمان (کشو، حباب‌های پیام و دکمه‌ها) در حالت فارسی.
- نگهداری هوشمند جهت LTR برای کدها، مسیر فایل‌ها، لینک‌ها و کلیدهای API.

---

### 🛠️ ساخت و اجرا از سورس کد

پروژه‌ی Vega Agent هم از طریق **Android Studio / Gradle** و هم با اسکریپت سبک Shell قابل ساخت است:

```bash
# ساخت APK امضاشده بدون وابستگی به Gradle
./mkapk.sh

# اجرای مجموعه تست‌های رفتاری و ساختاری بدون نیاز به دستگاه
./runtests.sh
```

---

<a name="english"></a>
## 🇬🇧 English

**Vega Agent** is an elite, fully autonomous, **100% local** AI agent for Android. It equips your smartphone with direct filesystem operations, web research capabilities, multi-step task execution, and automated code editing — without reliance on any intermediate server.

Simply supply your own API key (from OpenAI, Anthropic Claude, Google Gemini, or any OpenAI-compatible gateway like OpenRouter, Azure, Ollama, or LM Studio), and your assistant runs locally — communicating directly with the provider of your choice.

### ✨ Key Capabilities

#### 🤖 1. Multi-Provider & Auto-Protocol Detection
- Native support for **OpenAI**, **Anthropic (Claude)**, and **Google Gemini**.
- Broad compatibility with OpenAI-compatible gateways (OpenRouter, Azure, Ollama, LM Studio).
- **Wire-protocol Inference:** Automatically determines the required API payload layout based on your Base URL.

#### 📂 2. Full Filesystem Tooling
- File reading, writing, and atomic diff-based edits.
- Deep text search and glob pattern filtering.
- Directory management, file move/rename/delete, ZIP archive extraction, and native PDF parsing.

#### 🛡️ 3. Granular Assistant Freedom Modes
- **Automatic:** Independent action and autonomous file edits for maximum efficiency.
- **Planning:** Read-only mode; drafts an execution blueprint and awaits manual confirmation.
- **Accepting:** Explicit per-action authorization before touching any file or external tool.

#### 🧠 4. Deep Reasoning & Dynamic Sub-Agent Workflows
- **Reasoning Effort Control:** Fine-tune the model's thinking budget (from Low to Maximum).
- **Dynamic Workflow:** Automatically decomposes multi-phase problems and delegates sub-tasks to parallel, isolated sub-agents.
- **Transparent Thought Trace:** Live visibility into model `<think>` blocks, tool execution loops, and error recovery steps.

#### 🌐 5. Web Research & Human-Mode Browsing
- Integrated DuckDuckGo and Bing search connectors.
- **Human Mode:** Bypass JavaScript / Cloudflare anti-bot checks using a real embedded WebView to acquire and reuse clearance session cookies.
- Configurable local network access for internal server management.

#### 🔒 6. Enterprise-Grade Security & Privacy
- API keys encrypted via **AES-256-GCM**, backed by **AndroidKeyStore** hardware security modules.
- Full transparency when a device lacks a hardware security module.
- **SSRF & Metadata Guard:** Protects against unintentional calls to internal network resources and cloud metadata endpoints (e.g. `metadata.google.internal`).
- **Preflight Validation:** Instant feedback on invalid keys, bad endpoints, or model mismatches before any request is sent.

#### ⚡ 7. Uninterrupted Background Execution
- **Foreground Service Architecture:** Prevents task suspension when switching apps or locking the screen.
- In-app configuration helper for OEM battery-optimization policies (Xiaomi, Samsung, Huawei, OPPO).

#### 🎨 8. Native Bilingual & Full RTL Support
- Native Farsi and English UI translation.
- Complete visual layout mirroring (drawer, message bubbles, buttons) for RTL languages.
- Smart LTR preservation for code blocks, file paths, links, and API keys.

### 🛠️ Building & Testing

```bash
# Build a signed release APK using the lightweight script
./mkapk.sh

# Run the full suite of behavioral and structural tests, no device required
./runtests.sh
```

---

<div align="center">

### 🛡️ Privacy First
*Vega Agent is 100% serverless on the developer side. Your conversations, workspace files, and API keys stay strictly on your personal device.*

Made with ❤️ for power users who demand control, privacy, and performance.

</div>
