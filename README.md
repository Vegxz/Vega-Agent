<div align="center">

# ⚡ Vega Agent

### **ایجنت هوش مصنوعی درون‌دستگاهی برای اندروید**
*یک فضای کاری خصوصی، قدرتمند و کاملاً قابل تنظیم — با کلید API خودتان (BYOK)*

[![Platform](https://img.shields.io/badge/Platform-Android_6.0+-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://android.com)
[![Language](https://img.shields.io/badge/Language-Kotlin-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Security](https://img.shields.io/badge/Security-AES--256--GCM-red?style=for-the-badge)](#-امنیت-و-حریم-خصوصی)
[![Privacy](https://img.shields.io/badge/Privacy-100%25_On--Device-00C853?style=for-the-badge)](#-امنیت-و-حریم-خصوصی)
[![License](https://img.shields.io/badge/License-MIT-blue?style=for-the-badge)](LICENSE)

[🇮🇷 **فارسی**](#-فارسی) &nbsp;|&nbsp; [🇬🇧 **English**](#-english)

</div>

---

## 📸 گالری تصاویر

<div align="center">

| 📂 مدیریت فایل | 🛡️ سطح دسترسی | ⚙️ تنظیمات ارائه‌دهنده |
| :---: | :---: | :---: |
| <img src="screenshots/Screenshot%202.jpg" width="240" alt="File Picker"> | <img src="screenshots/Screenshot%203.jpg" width="240" alt="Assistant Mode"> | <img src="screenshots/Screenshot%206.jpg" width="240" alt="Settings"> |
| انتخاب فایل‌ها و پوشه‌ها | حالت خودکار / تأیید مرحله‌ای | پشتیبانی چند ارائه‌دهنده |

| 🧠 استدلال و ابزارها | 🔍 شفافیت اجرا | 🌐 جستجوی وب |
| :---: | :---: | :---: |
| <img src="screenshots/Screenshot%201.jpg" width="240" alt="Reasoning Settings"> | <img src="screenshots/Screenshot%204.jpg" width="240" alt="Thought Trace"> | <img src="screenshots/Screenshot%205.jpg" width="240" alt="Web Search"> |
| تنظیم قدرت استدلال | نمایش مراحل فکر و ابزار | جستجو و مرور هوشمند وب |

</div>

---

<a name="فارسی"></a>
## 🇮🇷 فارسی

**Vega Agent** یک ایجنت هوش مصنوعی مستقل و کاملاً درون‌دستگاهی برای اندروید است؛ با دسترسی کامل به فایل‌سیستم، جستجوی وب، اجرای کارهای چندمرحله‌ای و ویرایش کد — بدون نیاز به هیچ سرور واسط. فقط کافیست کلید API خود (OpenAI، Anthropic Claude، Google Gemini یا گیت‌وی‌هایی مانند OpenRouter، Azure، Ollama و LM Studio) را وارد کنید.

### ✨ ویژگی‌های کلیدی

**🤖 چند ارائه‌دهنده + تشخیص خودکار پروتکل**
پشتیبانی بومی از OpenAI، Claude و Gemini، به‌علاوه هر گیت‌وی سازگار با OpenAI؛ تشخیص پروتکل فقط از روی Base URL.

**📂 ابزارهای واقعی فایل‌سیستم**
خواندن/نوشتن/ویرایش فایل با نمایش Diff رنگی، جستجوی متن و Glob، مدیریت پوشه‌ها، و کار مستقیم با ZIP و PDF.

**🛡️ حالت‌های دسترسی ایجنت**
- **Automatic** — اجرای کاملاً خودکار
- **Planning** — فقط‌خواندنی، ارائه نقشه راه پیش از اجرا
- **Accepting** — تأیید کاربر پیش از هر اقدام

**🧠 استدلال پیشرفته و Dynamic Workflow**
تنظیم قدرت استدلال (Low تا Maximum)، واگذاری کارهای پیچیده به زیرایجنت‌های موازی، و نمایش شفاف مراحل فکر (`<think>`) و فراخوانی ابزارها.

**🌐 جستجو و مرور هوشمند وب**
جستجو از طریق DuckDuckGo و Bing، «حالت انسانی» برای عبور از Cloudflare با WebView واقعی، و کنترل دسترسی به شبکه محلی.

**🔒 امنیت و حریم خصوصی**
رمزنگاری کلید API با AES-256-GCM روی AndroidKeyStore، مسدودسازی خودکار دسترسی به آدرس‌های حساس داخلی و متادیتای ابری، و بررسی Preflight برای تشخیص سریع خطاهای تنظیمات.

**⚡ اجرای پایدار در پس‌زمینه**
Foreground Service برای ادامه پردازش حتی هنگام خروج از اپ، به‌همراه راهنمای مدیریت باتری برای شیائومی، سامسونگ، هواوی و اوپو.

**🎨 رابط دوزبانه (RTL/LTR)**
پشتیبانی کامل فارسی و انگلیسی با آینه‌سازی چیدمان در حالت فارسی، و حفظ جهت LTR برای کد، مسیر فایل، لینک و کلید API.

### 🛠️ ساخت از سورس

```bash
# ساخت APK امضاشده بدون Gradle
./mkapk.sh

# اجرای تست‌ها
./runtests.sh
```

---

<a name="english"></a>
## 🇬🇧 English

**Vega Agent** is a fully on-device AI agent for Android with direct filesystem access, web research, multi-step task execution, and code editing — no intermediate servers. Just supply your own API key (OpenAI, Anthropic Claude, Google Gemini, or gateways like OpenRouter, Azure, Ollama, LM Studio).

### ✨ Key Features

**🤖 Multi-Provider & Auto-Protocol Detection**
Native support for OpenAI, Claude, and Gemini, plus any OpenAI-compatible gateway — protocol inferred automatically from the Base URL.

**📂 Full Filesystem Tooling**
File read/write with colored diffs, deep text & Glob search, folder management, ZIP extraction, and native PDF parsing.

**🛡️ Assistant Freedom Modes**
- **Automatic** — fully autonomous execution
- **Planning** — read-only, drafts a plan before acting
- **Accepting** — confirms every action with the user

**🧠 Deep Reasoning & Dynamic Workflows**
Adjustable reasoning effort (Low–Maximum), task decomposition into parallel sub-agents, and a live `<think>` / tool-call trace.

**🌐 Web Research & Human-Mode Browsing**
DuckDuckGo/Bing search, a "Human Mode" that bypasses Cloudflare using a real WebView, and configurable local-network access.

**🔒 Security & Privacy**
API keys encrypted with AES-256-GCM via AndroidKeyStore, SSRF/metadata-endpoint protection, and Preflight validation for fast config-error detection.

**⚡ Reliable Background Execution**
Foreground Service architecture prevents task suspension, plus in-app battery-optimization guidance for Xiaomi, Samsung, Huawei, and OPPO.

**🎨 Native Bilingual UI (RTL/LTR)**
Full Farsi/English support with mirrored RTL layout, while code, URLs, file paths, and API keys stay strictly LTR.

### 🛠️ Build & Test

```bash
# Build a signed APK without Gradle
./mkapk.sh

# Run the test suite
./runtests.sh
```

---

<div align="center">

### 🛡️ Privacy First
*Vega Agent is 100% serverless. Your conversations, files, and API keys never leave your device.*

Made with ❤️ for power users who demand control, privacy, and performance.

</div>
