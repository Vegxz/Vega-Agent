package com.vepro.code

import android.content.Context

/**
 * Every string the interface shows, in both languages it ships in.
 *
 * ### One line, one string, two languages
 *
 * Each entry is a computed getter over a pair of literals. That is the whole
 * design, and it is chosen against the two shapes this file has had before:
 *
 *  - v6 held every string THREE times — a Persian field default, an English
 *    overlay applied by `apply()`, and a Persian restore — kept in step only by
 *    a test that compared the three key sets. Adding a string meant editing
 *    three places and any miss was a silently untranslated label.
 *  - v1 collapsed that to one immutable English table, which was correct for an
 *    English-only build and has no room for a second language at all.
 *
 * A getter has neither problem. The two renderings sit on the same line, so
 * they cannot drift and a missing translation is a compile error rather than a
 * runtime surprise. Nothing is cached, so switching language takes effect on the
 * next read with no table to rebuild and no screen able to disagree with
 * another about which language it is in.
 *
 * ### What is NOT translated
 *
 * The product name. `APP_NAME` is "Vega Agent" in both languages, as are the
 * protocol names and the version string — they are identifiers, not prose.
 *
 * ### This file is about the INTERFACE
 *
 * What the model writes is a separate matter: it answers in whatever language it
 * is written to, and every prose view lays its text out from its own first
 * strong character, so a Persian answer reads correctly even with the interface
 * in English, and an English answer reads correctly with the interface in
 * Persian.
 */
object Fa {

    /**
     * True when the interface is Persian.
     *
     * Read by every getter below and by [Lang], which derives layout direction
     * and numerals from it. Volatile because [apply] runs on whichever thread
     * entered a screen while getters are read from the UI thread.
     */
    @Volatile
    internal var farsi: Boolean = false
        private set

    /**
     * Points the table at the stored language preference.
     *
     * Called on entry to every screen and on a cold service start. It writes one
     * boolean — there is no table to rebuild — and drops [Lang]'s cached
     * direction so the two can never disagree about which way the screen reads.
     */
    fun apply(context: Context) {
        farsi = Prefs(context).language() == "fa"
        Lang.invalidate()
    }

    val ACT_ARCHIVE: String get() = if (farsi) "بررسی آرشیو" else "Inspect archive"
    val ACT_DELETE: String get() = if (farsi) "حذف کردن" else "Delete"
    val ACT_DOWNLOAD: String get() = if (farsi) "دانلود فایل" else "Download a file"
    val ACT_EDIT: String get() = if (farsi) "ویرایش فایل" else "Edit a file"
    val ACT_LIST: String get() = if (farsi) "دیدن محتوای پوشه" else "List a folder"
    val ACT_MEMORY: String get() = if (farsi) "حافظه‌ی بلندمدت" else "Long-term memory"
    val ACT_MKDIR: String get() = if (farsi) "ساخت پوشه" else "Create a folder"
    val ACT_MOVE: String get() = if (farsi) "جابه‌جایی یا تغییر نام" else "Move or rename"
    val ACT_OTHER: String get() = if (farsi) "اجرای ابزار" else "Run a tool"
    val ACT_PDF: String get() = if (farsi) "خواندن PDF" else "Read a PDF"
    val ACT_READ: String get() = if (farsi) "خواندن فایل" else "Read a file"
    val ACT_SEARCH: String get() = if (farsi) "جستجو در فایل‌ها" else "Search files"
    val ACT_TASK: String get() = if (farsi) "اجرای زیروظیفه" else "Delegated sub-task"
    val ACT_WEB_FETCH: String get() = if (farsi) "باز کردن صفحه‌ی وب" else "Open a web page"
    val ACT_WEB_SEARCH: String get() = if (farsi) "جستجوی وب" else "Web search"
    val ACT_WRITE: String get() = if (farsi) "ساخت فایل تازه" else "Create a new file"
    val APPROVE_ALWAYS: String get() = if (farsi) "همیشه اجازه بده" else "Always allow"
    val APPROVE_ALWAYS_NOTE: String get() = if (farsi) "این ابزار تا بسته‌شدن برنامه دیگر پرسیده نمی‌شود." else "This tool won't be asked about again until the app restarts."
    val APPROVE_ASK: String get() = if (farsi) "اجازه می‌دهید؟" else "Do you allow this?"
    val CHAT_MENU_DELETE: String get() = if (farsi) "حذف گفتگو" else "Delete"
    val CHAT_MENU_PIN: String get() = if (farsi) "سنجاق کردن" else "Pin"
    val CHAT_MENU_RENAME: String get() = if (farsi) "تغییر نام" else "Rename"
    val CHAT_MENU_UNPIN: String get() = if (farsi) "برداشتن سنجاق" else "Unpin"
    val CHAT_RENAME_TITLE: String get() = if (farsi) "نام تازه" else "New name"
    val EDIT_PROGRESS: String get() = if (farsi) "در حال ویرایش" else "Editing"
    val ERR_NO_BROWSER: String get() = if (farsi) "مرورگری برای باز کردن این پیوند پیدا نشد" else "No app found to open this link"
    val ERR_SAVE: String get() = if (farsi) "ذخیره نشد؛ فضای دستگاه را بررسی کنید" else "Could not save — check the device's storage"
    val PLAN_BEST: String get() = if (farsi) "پیشنهاد" else "Recommended"
    val APPROVE_REJECT: String get() = if (farsi) "رد" else "Reject"
    val APPROVE_RUN: String get() = if (farsi) "تأیید و اجرا" else "Approve and run"
    val APPROVE_SUBTITLE: String get() = if (farsi) "دستیار می\u200cخواهد این عملیات را اجرا کند" else "The assistant wants to run this operation"
    val APPROVE_TITLE: String get() = if (farsi) "تأیید عملیات" else "Approve operation"
    val APP_NAME: String get() = "Vega Agent"
    val ATTACH_FILE: String get() = if (farsi) "افزودن فایل" else "Add file"
    val ATTACH_IMAGE: String get() = if (farsi) "افزودن عکس" else "Add image"
    val BROWSER_EMPTY: String get() = if (farsi) "این پوشه خالی است" else "This folder is empty"
    val BROWSER_PICK: String get() = if (farsi) "انتخاب همین پوشه" else "Select this folder"
    val BROWSER_SUB: String get() = if (farsi) "پوشه‌ها را باز کنید و فایلی را برگزینید" else "Open folders and select a file"
    val BROWSER_TITLE: String get() = if (farsi) "انتخاب فایل" else "Select file"
    val BROWSER_UP: String get() = if (farsi) "پوشه بالاتر" else "Parent folder"
    val CANCEL: String get() = if (farsi) "انصراف" else "Cancel"
    val CHATS: String get() = if (farsi) "گفتگوها" else "Chats"
    val CLOSE: String get() = if (farsi) "بستن" else "Close"
    val CONFIRM: String get() = if (farsi) "تأیید" else "Confirm"
    val COPIED: String get() = if (farsi) "کپی شد" else "Copied"
    val COPY: String get() = if (farsi) "کپی" else "Copy"
    val DELETE: String get() = if (farsi) "حذف" else "Delete"
    val DELETE_CHAT_MSG: String get() = if (farsi) "این گفتگو برای همیشه پاک می‌شود. این کار قابل بازگشت نیست." else "This chat will be permanently deleted."
    val DELETE_CHAT_TITLE: String get() = if (farsi) "حذف گفتگو؟" else "Delete chat?"
    val DIFF_ADDED: String get() = if (farsi) "افزوده\u200cشده" else "Added"
    val DIFF_REMOVED: String get() = if (farsi) "حذف\u200cشده" else "Removed"
    val EDIT: String get() = if (farsi) "اصلاح" else "Edit"
    val ERR_AUTH: String get() = if (farsi) "کلید API نامعتبر است یا منقضی شده." else "The API key is invalid or expired."
    val ERR_BADREQ: String get() = if (farsi) "درخواست نامعتبر است" else "Invalid request"
    val ERR_FORBIDDEN: String get() = if (farsi) "دسترسی رد شد. کلید API مجوز این عملیات را ندارد." else "Access denied. The API key is not authorized for this operation."
    val ERR_MAXSTEPS: String get() = if (farsi) "به بیشینه گام\u200cهای این نوبت رسیدم. اگر بخواهید، بگویید تا ادامه دهم." else "I reached the maximum steps for this turn. Ask me to continue if you want."
    val ERR_NOTFOUND: String get() = if (farsi) "آدرس یا نام مدل یافت نشد. تنظیمات را بررسی کنید." else "The URL or model was not found. Check your settings."
    val ERR_REDIRECT: String get() = if (farsi) "نشانی سرویس بارها تغییر مسیر داد؛ آدرس پایه را در تنظیمات بررسی کنید." else "The endpoint redirected too many times — check the base URL in Settings."
    val SET_TEST_UNREADABLE: String get() = if (farsi) "اتصال برقرار شد اما پاسخ قابل خواندن نبود." else "Connected, but the reply could not be read."
    val ERR_NO_NET: String get() = if (farsi) "دسترسی به اینترنت برقرار نیست. اتصال شبکه را بررسی کنید." else "No internet connection. Check your network."
    val ERR_NO_PICKER: String get() = if (farsi) "برنامه انتخاب فایل یافت نشد" else "No file picker found"
    val ERR_OVERLOAD: String get() = if (farsi) "سرور پرترافیک است. کمی بعد دوباره تلاش می\u200cشود." else "The server is busy. Retrying shortly."
    val ERR_RATE: String get() = if (farsi) "محدودیت نرخ درخواست. کمی صبر کنید و دوباره تلاش کنید." else "Rate limit reached. Wait a moment and try again."
    val ERR_READ_FILE: String get() = if (farsi) "فایل خوانده نشد" else "Could not read file"
    val ERR_SERVER: String get() = if (farsi) "خطای سرور. لحظاتی بعد دوباره تلاش کنید." else "Server error. Please try again shortly."
    val ERR_TIMEOUT: String get() = if (farsi) "زمان پاسخ\u200cگویی سرور تمام شد. دوباره تلاش کنید." else "The server timed out. Please try again."
    val ERR_UNKNOWN: String get() = if (farsi) "خطای ناشناخته" else "Unknown error"
    val INPUT_HINT: String get() = if (farsi) "پیام خود را بنویسید…" else "Write a message…"
    val MODE_ACCEPT: String get() = if (farsi) "تأییدی" else "Accepting"
    val MODE_ACCEPT_DESC: String get() = if (farsi) "پیش از هر تغییر، اجازه می\u200cگیرد." else "Asks for permission before every change."
    val MODE_AUTO: String get() = if (farsi) "خودکار" else "Automatic"
    val MODE_AUTO_DESC: String get() = if (farsi) "دستیار مستقل عمل می\u200cکند و فایل\u200cها را آزادانه ویرایش می\u200cکند." else "Acts independently and edits files freely."
    val MODE_PLAN: String get() = if (farsi) "برنامه\u200cریزی" else "Planning"
    val MODE_PLAN_DESC: String get() = if (farsi) "فقط\u200cخواندنی؛ ابتدا یک نقشه پیشنهاد می\u200cدهد و منتظر تأیید می\u200cماند." else "Read-only; proposes a plan and waits for approval."
    val MODE_SUBTITLE: String get() = if (farsi) "میزان اختیار دستیار را انتخاب کنید" else "Choose how much freedom the assistant has"
    val MODE_TITLE: String get() = if (farsi) "حالت دستیار" else "Assistant mode"
    val NEW_CHAT: String get() = if (farsi) "گفتگوی جدید" else "New chat"
    val NEW_FILE: String get() = if (farsi) "فایل جدید" else "New file"
    val NO_CHATS: String get() = if (farsi) "هنوز گفتگویی نیست" else "No chats yet"
    val PERM_GRANT: String get() = if (farsi) "اعطای دسترسی" else "Grant access"
    val PERM_LATER: String get() = if (farsi) "فعلاً نمی‌خواهم" else "Not now"
    val PERM_HINT: String get() = if (farsi) "«دسترسی به همه فایل\u200cها» را در تنظیمات سیستم فعال کنید" else "Enable «all files access» in system settings"
    val PERM_MSG: String get() = if (farsi) "برای دسترسی دستیار به پوشه‌ها، اجازه «دسترسی به همه فایل‌ها» را بدهید" else "Grant all-files access so the assistant can work with folders."
    val PLAN_KEEP: String get() = if (farsi) "ادامه گفتگو" else "Continue chat"
    val PLAN_QUESTIONS: String get() = if (farsi) "پرسش\u200cها" else "Questions"
    val PLAN_RUN: String get() = if (farsi) "اجرای نقشه" else "Run plan"
    val PLAN_RUN_MSG: String get() = if (farsi) "نقشه را تأیید می\u200cکنم. لطفاً آن را گام\u200cبه\u200cگام اجرا کن." else "I approve the plan. Please execute it step by step."
    val PLAN_STEPS: String get() = if (farsi) "گام\u200cها" else "Steps"
    val PLAN_SUBTITLE: String get() = if (farsi) "دستیار این برنامه را پیشنهاد می\u200cدهد" else "The assistant proposes this plan"
    val PLAN_TITLE: String get() = if (farsi) "نقشه پیشنهادی" else "Proposed plan"
    val Q_APPLY_NOTE: String get() = if (farsi) "پس از انتخاب، در صورت نیاز تغییرات با تأیید شما اعمال می\u200cشود." else "After selection, changes are applied with your approval when needed."
    val Q_CUSTOM: String get() = if (farsi) "پیشنهاد خودم" else "My own suggestion"
    val Q_CUSTOM_HINT: String get() = if (farsi) "راه\u200cحل دلخواه خود را بنویسید…" else "Write your preferred solution…"
    val Q_SEND: String get() = if (farsi) "ارسال پاسخ" else "Send answer"
    val Q_TITLE: String get() = if (farsi) "یک تصمیم لازم است" else "A decision is needed"
    val RETRY: String get() = if (farsi) "تلاش دوباره" else "Retry"
    val RETRYING: String get() = if (farsi) "در حال تلاش مجدد" else "Retrying"
    val SAVE: String get() = if (farsi) "ذخیره" else "Save"
    val SEND: String get() = if (farsi) "ارسال" else "Send"
    val SETTINGS: String get() = if (farsi) "تنظیمات" else "Settings"
    val SETUP_MSG: String get() = if (farsi) "پیش از گفتگو، کلید API، آدرس پایه و مدل را در تنظیمات وارد کنید." else "Set your API key, base URL, and model in Settings before chatting."
    val SETUP_OPEN: String get() = if (farsi) "باز کردن تنظیمات" else "Open Settings"
    val SETUP_TITLE: String get() = if (farsi) "نیاز به راه‌اندازی" else "Setup required"
    val SET_API_KEY: String get() = if (farsi) "کلید API" else "API key"
    val SET_BASE_URL: String get() = if (farsi) "آدرس پایه (Base URL)" else "Base URL"
    val SET_BEHAVIOR: String get() = if (farsi) "رفتار" else "Behavior"
    val SET_CUSTOM: String get() = if (farsi) "دستورهای سفارشی" else "Custom instructions"
    val SET_CUSTOM_HINT: String get() = if (farsi) "دستورهای اضافه که به پرامپت سیستم افزوده می‌شود…" else "Additional system instructions…"
    val SET_HELP: String get() = if (farsi) "برای endpoint سفارشی، پروتکل را دستی انتخاب کنید." else "Select the protocol manually for custom endpoints."
    val SET_KEYSTORE_UNAVAILABLE: String get() = if (farsi) "کلید ذخیره شد، ولی این دستگاه رمزگذاری سخت‌افزاری ندارد." else "Key saved, but this device has no hardware encryption."
    val SET_MAXTOK: String get() = if (farsi) "بیشینه توکن پاسخ" else "Maximum response tokens"
    val SET_TIMEOUT: String get() = if (farsi) "زمان انتظار پاسخ (ثانیه)" else "Response timeout (seconds)"
    val SET_TIMEOUT_H: String get() = if (farsi) "اگر سرویس این مدت هیچ داده‌ای نفرستد، درخواست لغو می‌شود. پاسخ در حال دریافت این زمان را صفر می‌کند، پس پاسخ‌های طولانی مشکلی ندارند. برای مدل‌های کند یا محلی مقدار بیشتری بگذارید. بازه: ۱۰ تا ۱۸۰۰ ثانیه." else "Cancel the request if the service sends no data for this long. Incoming output resets the clock, so long answers are fine. Raise it for slow or local models. Range: 10-1800 seconds."
    val SET_MODEL: String get() = if (farsi) "نام مدل" else "Model name"
    val SET_NEED_FIELDS: String get() = if (farsi) "آدرس پایه و نام مدل الزامی است" else "Base URL and model are required"
    val SET_PRESET: String get() = if (farsi) "انتخاب سریع" else "Quick presets"
    val SET_PROTOCOL: String get() = if (farsi) "پروتکل" else "Protocol"
    val SET_PROTO_ANTHRO: String get() = "Anthropic"
    val SET_PROTO_AUTO: String get() = if (farsi) "خودکار" else "Auto"
    val SET_PROTO_GEMINI: String get() = "Gemini"
    val SET_PROTO_OPENAI: String get() = "OpenAI"
    val SET_PROVIDER: String get() = if (farsi) "ارائه‌دهنده هوش مصنوعی" else "AI provider"
    val SET_SAVE: String get() = if (farsi) "ذخیره تنظیمات" else "Save settings"
    val SET_SAVED: String get() = if (farsi) "ذخیره شد" else "Saved"
    val SET_TEMP: String get() = if (farsi) "خلاقیت (Temperature)" else "Temperature"
    val SET_TEST: String get() = if (farsi) "تست اتصال" else "Test connection"
    val SET_TESTING: String get() = if (farsi) "در حال بررسی…" else "Testing…"
    val SET_TEST_OK: String get() = if (farsi) "اتصال برقرار شد ✓" else "Connected ✓"
    val SET_THINKING: String get() = if (farsi) "استدلال گام\u200cبه\u200cگام (Thinking)" else "Step-by-step reasoning (Thinking)"
    val SET_THINKING_H: String get() = if (farsi) "فقط برای مدل\u200cهای Claude؛ استدلال درونی مدل را نمایش می\u200cدهد." else "For Claude models only; shows the model's internal reasoning."
    val SET_THINK_LEVEL: String get() = if (farsi) "میزان تفکر" else "Reasoning effort"
    val SET_THINK_LEVEL_H: String get() = if (farsi) "هرچه بالاتر، مدل پیش از پاسخ عمیق‌تر فکر می‌کند. استدلالش در بخش «بررسی درخواست» نمایش داده می‌شود." else "Higher effort makes the model reason longer before it answers. Its reasoning appears inside the review section."
    val SET_WEB: String get() = if (farsi) "ابزار جست‌وجوی وب" else "Web search tool"
    val SET_WORKFLOW: String get() = if (farsi) "گردش‌کار پویا" else "Dynamic Workflow"
    val SET_WORKFLOW_H: String get() = if (farsi) "کار را مرحله‌به‌مرحله به ایجنت‌های کوچک می‌سپارد. دقیق‌تر، ولی کندتر." else "Splits a job into phases and delegates each to a focused sub-agent. More thorough, but slower."
    val NET_BLOCK_EMPTY: String get() = if (farsi) "نشانی شبکه خالی است" else "The network address is empty"
    val NET_BLOCK_MALFORMED: String get() = if (farsi) "نشانی شبکه معتبر نیست" else "The network address is not valid"
    val NET_BLOCK_SCHEME: String get() = if (farsi) "فقط نشانی https پذیرفته می‌شود" else "Only https addresses are accepted"
    val NET_BLOCK_PLAINTEXT: String get() = if (farsi) "برای نشانی اینترنتی باید https باشد تا کلید شما رمزنگاری‌شده ارسال شود" else "A public endpoint must use https so your key is sent encrypted"
    val NET_BLOCK_LOCAL: String get() = if (farsi) "دسترسی ابزارها به شبکهٔ محلی خاموش است؛ در تنظیمات آن را روشن کنید" else "Local-network access for tools is off — turn it on in Settings"
    val NET_BLOCK_METADATA: String get() = if (farsi) "دسترسی به سرویس متادیتای ابری مجاز نیست" else "Access to cloud metadata services is not allowed"
    val SET_LOCAL_NET: String get() = if (farsi) "دسترسی به شبکهٔ محلی" else "Local network access"
    val SET_LOCAL_NET_H: String get() = if (farsi) "اجازهٔ دسترسی ابزارها به شبکهٔ محلی. سرویس خودتان همیشه آزاد است." else "Lets tools reach your local network. Your own endpoint is always allowed."
    val SET_KEY_ROUTER: String get() = if (farsi) "روتر کلیدها" else "Key Router"
    val SET_KEY_ROUTER_H: String get() = if (farsi) "کلیدهای جایگزین اضافه کنید (تا ۵۰ کلید). اگر کلید فعال هنگام گفتگو به محدودیت (Rate Limit) بخورد، برنامه بی‌صدا و بدون قطع شدن کار، کلید بعدی را امتحان می‌کند." else "Add fallback keys (up to 50). If the active key hits a rate limit while chatting, the next key is tried silently — the work never stops."
    val SET_KEY_ADD: String get() = if (farsi) "افزودن" else "Add"
    val SET_KEY_EMPTY: String get() = if (farsi) "هنوز کلیدی اضافه نشده است" else "No keys added yet"
    val SET_KEY_FULL: String get() = if (farsi) "حداکثر ۵۰ کلید می‌توانید اضافه کنید" else "You can add up to 50 keys"
    val SET_KEY_DUP: String get() = if (farsi) "این کلید قبلاً اضافه شده است" else "This key is already added"
    val SET_RESET: String get() = if (farsi) "بازنشانی تنظیمات" else "Reset settings"
    val SET_RESET_H: String get() = if (farsi) "همه تنظیمات به حالت اولیه برمی‌گردد؛ گفتگوها حفظ می‌شوند." else "Every setting returns to its default; your chats are kept."
    val SET_RESET_MSG: String get() = if (farsi) "این کار همه تنظیمات (از جمله کلیدهای API) را پاک می‌کند و قابل بازگشت نیست." else "This wipes all settings (including API keys) and cannot be undone."
    val SET_RESET_DONE: String get() = if (farsi) "تنظیمات بازنشانی شد" else "Settings were reset"
    val SUG_1: String get() = if (farsi) "پوشه دانلود را نشان بده" else "Show my Downloads folder"
    val SUG_3: String get() = if (farsi) "در وب جست‌وجو کن" else "Search the web"
    val SVC_CHANNEL: String get() = if (farsi) "اجرای وظیفه" else "Task execution"
    val SVC_DONE: String get() = if (farsi) "وظیفه کامل شد" else "Task completed"
    val SVC_TEXT: String get() = if (farsi) "وظیفه در پس\u200cزمینه اجرا می\u200cشود" else "Task running in the background"
    val SVC_TITLE: String get() = if (farsi) "Vega Agent در حال کار است" else "Vega Agent is working"
    val TAGLINE: String get() = if (farsi) "دستیار کدنویسی هوشمند روی دستگاه شما" else "Your on-device intelligent coding assistant"
    val SET_THEME: String get() = if (farsi) "پوسته (تم)" else "Theme"
    val SET_THEME_H: String get() = if (farsi) "از تم سیستم پیروی کنید یا حالت روشن/تاریک را انتخاب کنید." else "Follow the system theme or choose light/dark mode."
    val THEME_SYSTEM: String get() = if (farsi) "سیستم" else "System"
    val THEME_LIGHT: String get() = if (farsi) "روشن" else "Light"
    val THEME_DARK: String get() = if (farsi) "تاریک" else "Dark"
    val SECURITY_REDACTED: String get() = if (farsi) "\n\n[هشدار امنیتی: محتوای حاوی کلید API شناسایی و قبل از نوشتن حذف شد. کلید هرگز نباید در فایل ذخیره شود.]" else "\n\n[Security warning: content containing an API key was detected and removed before writing. The key must never be stored in a file.]"
    val THINKING_LABEL: String get() = if (farsi) "استدلال مدل" else "Model reasoning"
    val TL_HIGH: String get() = if (farsi) "زیاد" else "High"
    val TL_LOW: String get() = if (farsi) "کم" else "Low"
    val TL_MAX: String get() = if (farsi) "حداکثر" else "Maximum"
    val TL_MED: String get() = if (farsi) "متوسط" else "Medium"
    val TL_XHIGH: String get() = if (farsi) "خیلی زیاد" else "Very high"
    val TOOL_RAN: String get() = if (farsi) "ابزار اجرا شد" else "Tool ran"
    val TOOL_RUNNING: String get() = if (farsi) "در حال اجرا" else "Running"
    val TRAIL_DELEGATING: String get() = if (farsi) "واگذاری به زیرایجنت" else "Delegating"
    val TRAIL_OPENING: String get() = if (farsi) "باز کردن صفحه" else "Opened page"
    val TRAIL_PAGES: String get() = if (farsi) "صفحه" else "pages"
    val TRAIL_PANEL_TITLE: String get() = if (farsi) "روند بررسی" else "Thoughts"
    val TRAIL_RESULTS: String get() = if (farsi) "نتیجه" else "results"
    val TRAIL_RESULTS_TITLE: String get() = if (farsi) "نتایج وب" else "Web Results"
    val TRAIL_RETRYING: String get() = if (farsi) "تلاش دوباره" else "Retrying"
    val TRAIL_SEARCHING: String get() = if (farsi) "جست‌وجو" else "Searching"
    val TRAIL_SECONDS: String get() = if (farsi) "%s ثانیه" else "%ss"
    val TRAIL_THINKING: String get() = if (farsi) "بررسی درخواست" else "Reviewing request"
    val TRAIL_THOUGHT_FOR: String get() = if (farsi) "بررسی در %s ثانیه" else "Reviewed in %ss"
    val TRAIL_WORKING: String get() = if (farsi) "در حال کار" else "Working"
    val WELCOME_READY: String get() = if (farsi) "دستیار کد روی دستگاه شما؛ برای بررسی و ویرایش فایل‌ها، جست‌وجوی وب و تکمیل کارها." else "An on-device coding assistant that can inspect files, edit projects, search the web, and complete tasks."
    val WELCOME_SETUP: String get() = if (farsi) "برای شروع، کلید API خود را در تنظیمات وارد کنید." else "Set your API key in Settings to begin."
    val WF_DONE: String get() = if (farsi) "انجام شد" else "Done"
    val WF_FAILED: String get() = if (farsi) "ناموفق" else "Failed"
    val WF_PENDING: String get() = if (farsi) "در انتظار" else "Queued"
    val WF_RUNNING: String get() = if (farsi) "در حال اجرا" else "Running"
    val WF_STEPS: String get() = if (farsi) "%s گام" else "%s steps"
    val WF_TITLE: String get() = if (farsi) "گردش‌کار پویا" else "Dynamic Workflow"
    val TRAIL_CHANGES: String get() = if (farsi) "تغییر فایل" else "File changes"
    val TRAIL_COPY: String get() = if (farsi) "کپی روند" else "Copy activity"
    val TRAIL_COPIED: String get() = if (farsi) "روند بررسی کپی شد" else "Activity copied"
    val TRAIL_EDITED: String get() = if (farsi) "%s فایل" else "%s files"
    val TRAIL_FAILED: String get() = if (farsi) "ناموفق" else "Failed"
    val TRAIL_STOPPED: String get() = if (farsi) "متوقف شد" else "Stopped"
    val TRAIL_STEPS: String get() = if (farsi) "%s گام" else "%s steps"
    val TRAIL_SUMMARY_NONE: String get() = if (farsi) "بدون تغییر" else "No changes"
    val TRAIL_THOUGHTS: String get() = if (farsi) "%s استدلال" else "%s thoughts"
    val TRAIL_MS: String get() = if (farsi) "%s میلی‌ثانیه" else "%sms"
    val TRAIL_CLIPPED: String get() = if (farsi) "بخشی از تغییرات" else "Part of the change"
    val TRAIL_REASONING: String get() = if (farsi) "استدلال" else "Reasoning"
    val TRAIL_SHOW_MORE: String get() = if (farsi) "نمایش کامل" else "Show all"
    val TRAIL_LIVE: String get() = if (farsi) "در حال انجام" else "In progress"
    val TRAIL_EMPTY: String get() = if (farsi) "کاری ثبت نشده است" else "Nothing recorded"
    val TRAIL_PROG_READING: String get() = if (farsi) "خواندن فایل" else "Reading file"
    val TRAIL_PROG_WRITING: String get() = if (farsi) "نوشتن فایل" else "Writing file"
    val TRAIL_PROG_SCANNING: String get() = if (farsi) "پویش پوشه‌ها" else "Scanning folders"
    val TRAIL_PROG_MATCHED: String get() = if (farsi) "%s مورد" else "%s matches"
    val TRAIL_PROG_DOWNLOAD: String get() = if (farsi) "دریافت %s" else "Downloaded %s"
    val TRAIL_PROG_PAGES: String get() = if (farsi) "صفحه %s" else "Page %s"
    val TRAIL_PROG_ENTRIES: String get() = if (farsi) "%s ورودی" else "%s entries"
    val WF_STOPPED: String get() = if (farsi) "متوقف شد" else "Stopped"
    val WF_QUEUED: String get() = if (farsi) "%s در انتظار" else "%s queued"
    val WF_LIVE: String get() = if (farsi) "در حال اجرا" else "Live"
    val WF_HISTORY: String get() = if (farsi) "پایان‌یافته" else "Finished"
    val CHAT_MENU_TITLE: String get() = if (farsi) "گفتگو" else "Conversation"
    val SET_VERSION: String get() = "v1"
    val PERM_TITLE: String get() = if (farsi) "دسترسی به فایل‌ها" else "File access"
    val PRE_NO_KEY: String get() = if (farsi) "کلید API تنظیم نشده است. بدون آن هیچ درخواستی فرستاده نمی‌شود." else "No API key is set. Nothing can be sent without one."
    val PRE_NO_MODEL: String get() = if (farsi) "نام مدل خالی است." else "The model name is empty."
    val PRE_BAD_ENDPOINT: String get() = if (farsi) "آدرس سرور معتبر نیست." else "The server address is not a valid URL."
    val PRE_MISMATCH: String get() = if (farsi) "کلید شما مال %s است ولی درخواست به %s فرستاده می‌شود؛ این سرور آن کلید را نمی‌پذیرد." else "Your key belongs to %s but the request goes to %s, which will not accept it."
    val PRE_MISMATCH_FIX: String get() = if (farsi) "در تنظیمات آدرس سرور را روی %s بگذارید: %s" else "In Settings, point the server address at %s: %s"
    val PRE_OPEN_SETTINGS: String get() = if (farsi) "رفتن به تنظیمات" else "Open Settings"
    val PRE_TITLE: String get() = if (farsi) "درخواست فرستاده نشد" else "Request not sent"
    val RUN_CONNECTING: String get() = if (farsi) "اتصال به سرویس" else "Connecting"
    val RUN_RETRY_N: String get() = if (farsi) "تلاش دوباره (%s از %s)" else "Retrying (%s of %s)"
    val RUN_FAILED_STEP: String get() = if (farsi) "درخواست ناموفق" else "Request failed"
    val RUN_GAVE_UP: String get() = if (farsi) "پاسخی از سرویس نرسید" else "No reply from the service"
    val ERR_DETAILS: String get() = if (farsi) "جزئیات" else "Details"
    val SET_CONNECTION: String get() = if (farsi) "اتصال" else "Connection"
    val SET_KEY_PLAIN: String get() = if (farsi) "این کلید بدون رمزگذاری سخت‌افزاری ذخیره شده است." else "This key is stored without hardware encryption."
    val SET_SUBTITLE: String get() = if (farsi) "سرویس، مدل و رفتار دستیار" else "Endpoint, model and how the agent works"
    val SET_ABOUT: String get() = if (farsi) "درباره" else "About"
    val SET_REASONING: String get() = if (farsi) "استدلال" else "Reasoning"
    val SET_TOOLS: String get() = if (farsi) "ابزارها و دسترسی‌ها" else "Tools and access"
    val SET_GENERATION: String get() = if (farsi) "تولید پاسخ" else "Generation"
    val SET_TEST_SHORT: String get() = if (farsi) "آزمایش" else "Test"
    val SET_CONN_UNTESTED: String get() = if (farsi) "هنوز آزمایش نشده" else "Not tested yet"
    val SET_TEMP_H: String get() = if (farsi) "مقدار کم پاسخ‌ها را دقیق و تکرارپذیر می‌کند؛ مقدار زیاد آن‌ها را متنوع‌تر." else "Low keeps answers focused and repeatable; high makes them more varied."
    val SET_TEMP_LOW: String get() = if (farsi) "دقیق" else "Precise"
    val SET_TEMP_HIGH: String get() = if (farsi) "متنوع" else "Varied"
    val SET_PROTOCOL_H: String get() = if (farsi) "حالت خودکار از آدرس سرویس و نام مدل تشخیص می‌دهد. برای gateway یا سرور شخصی خودتان یکی را دستی انتخاب کنید." else "Auto reads the endpoint and the model name. Choose one yourself for a gateway or a self-hosted server."
    val SET_CUSTOM_H: String get() = if (farsi) "اختیاری. به همهٔ درخواست‌ها، در کنار دستورهای پیش‌فرض، افزوده می‌شود." else "Optional. Added to every request, on top of the built-in instructions."
    val WILL_DELETE: String get() = if (farsi) "این مسیر حذف خواهد شد" else "This path will be deleted"
    val WORKING: String get() = if (farsi) "در حال کار…" else "Working…"
    val STOP: String get() = if (farsi) "توقف اجرا" else "Stop"
    val STOPPING: String get() = if (farsi) "در حال توقف…" else "Stopping…"
    val STOPPED: String get() = if (farsi) "اجرا متوقف شد" else "Stopped"
    val RUN_STALLED: String get() = if (farsi) "پاسخ نیمه‌کاره ماند\nبرای این گام چیزی تولید نشد. «ادامه» را بزنید تا از همان‌جا که ماند کار را تمام کنم." else "The response was left unfinished\nNothing was produced for this step. Tap Continue and I will resume from exactly where it stopped."
    val RUN_INTERRUPTED: String get() = if (farsi) "اجرا نیمه‌کاره ماند\nاحتمالاً سیستم برنامه را در پس‌زمینه بست. می‌توانید از همان‌جا که ماند ادامه دهید." else "The run was cut short\nThe system most likely closed the app while it was in the background. You can pick up from where it stopped."
    val RUN_CONTINUE: String get() = if (farsi) "ادامه بده" else "Continue"
    val RUN_CONTINUE_MSG: String get() = if (farsi) "ادامه بده و کار قبلی را دقیقاً از همان‌جا که ماند تا پایان کامل کن." else "Continue the previous task exactly from where it stopped until it is complete."
    val RUN_DISMISS: String get() = if (farsi) "بستن" else "Dismiss"
    val BATT_TITLE: String get() = if (farsi) "اجرای بدون وقفه" else "Uninterrupted execution"
    val BATT_MSG: String get() = if (farsi) "برای جلوگیری از توقف کارهای طولانی در پس‌زمینه، برنامه را از بهینه‌سازی باتری معاف کنید." else "Allow battery-optimization exemption so long tasks are not stopped in the background."
    val BATT_ALLOW: String get() = if (farsi) "اجازه می‌دهم" else "Allow"
    val BATT_LATER: String get() = if (farsi) "بعداً" else "Later"

    // ---- v1.1: failure reasons, real workflow, language, OEM survival ----
    val WF_PLANNED: String get() = if (farsi) "برنامه‌ریزی‌شده" else "Planned"
    val WF_TOPIC_MORE: String get() = if (farsi) "%s مورد دیگر" else "+%s more"
    val APPROVE_QUEUED_N: String get() = if (farsi) "%s مورد دیگر در انتظار" else "%s more waiting"
    val TRAIL_REJECTED: String get() = if (farsi) "رد شد" else "Declined"
    val TRAIL_REASON: String get() = if (farsi) "دلیل خطا" else "Why it failed"
    val TRAIL_OUTPUT: String get() = if (farsi) "خروجی ابزار" else "Tool output"
    val TRAIL_TAP_REASON: String get() = if (farsi) "برای دیدن دلیل بزنید" else "Tap to see why"
    val TRAIL_PROG_EDITING: String get() = if (farsi) "ویرایش فایل" else "Editing file"
    val TRAIL_PROG_BYTES: String get() = if (farsi) "تا اینجا %s" else "%s so far"
    val PLAN_APPROVED: String get() = if (farsi) "نقشه تأیید شد — تغییر به حالت تأییدی" else "Plan approved — switching to Accepting"
    val MODE_ESCALATED: String get() = if (farsi) "تأییدی" else "Accepting"
    val WF_AGENTS_LIVE: String get() = if (farsi) "%s در حال کار" else "%s working"
    val WF_AGENTS_DONE: String get() = if (farsi) "%s تکمیل شد" else "%s finished"
    val WF_AGENTS_NONE: String get() = if (farsi) "هنوز ایجنتی شروع نشده" else "No agents started yet"
    val WF_AGENT: String get() = if (farsi) "ایجنت %s" else "Agent %s"
    val WF_TOPIC: String get() = if (farsi) "در حال کار روی" else "Working on"
    val WF_PARALLEL: String get() = if (farsi) "%s همزمان" else "%s at a time"
    val SET_LANGUAGE: String get() = if (farsi) "زبان" else "Language"
    val SET_LANGUAGE_H: String get() = if (farsi) "زبان رابط برنامه. دستیار همیشه به همان زبانی پاسخ می‌دهد که شما می‌نویسید." else "The interface language. The assistant always answers in whatever language you write to it."
    val SET_LANGUAGE_FA: String get() = if (farsi) "فارسی" else "Persian"
    val SET_LANGUAGE_EN: String get() = "English"
    val LANG_TITLE: String get() = if (farsi) "زبان خود را انتخاب کنید" else "Choose your language"
    val LANG_SUBTITLE: String get() = if (farsi) "بعداً می‌توانید از تنظیمات تغییرش دهید." else "You can change this later in Settings."
    val LANG_FA_NOTE: String get() = "رابط کامل فارسی و راست‌چین"
    val LANG_EN_NOTE: String get() = "Full English interface"
    val CHAT_EARLIER: String get() = if (farsi) "نمایش پیام‌های پیشین" else "Show earlier messages"
    val CHAT_HIDDEN_N: String get() = if (farsi) "%s پیام پیشین" else "%s earlier messages"
    val BATT_AUTOSTART: String get() = if (farsi) "اجازهٔ اجرای خودکار" else "Allow autostart"
    val BATT_AUTOSTART_MSG: String get() = if (farsi) "سازندهٔ گوشی شما جدا از تنظیم خود اندروید، کار در پس‌زمینه را هم محدود می‌کند. صفحهٔ سازنده را باز کنید و به Vega اجازهٔ اجرای خودکار بدهید، وگرنه کارهای طولانی باز هم قطع می‌شوند." else "Your phone's manufacturer also blocks background work separately from Android's own setting. Open the manufacturer's screen and allow Vega to start on its own, or long tasks will still be killed."
    val BATT_OPEN: String get() = if (farsi) "باز کردن تنظیمات" else "Open settings"
    val ERR_NO_TEXT: String get() = if (farsi) "سرویس پاسخی برگرداند که هیچ متنی در آن نبود." else "The server returned a JSON response with no text in it."
    val ERR_EMPTY_REPLY: String get() = if (farsi) "سرویس پاسخ خالی برگرداند. دوباره تلاش نشد تا همان درخواست دو بار محاسبه نشود." else "The server returned an empty response. It was not retried, so the same request is not billed twice."
    val ERR_STREAM_GAP: String get() = if (farsi) "سرویس بیش از زمان مجاز هیچ داده‌ای نفرستاد." else "The server stopped sending data for longer than the timeout allows."
    val ERR_BAD_STREAM: String get() = if (farsi) "سرویس جریانی فرستاد که برنامه نتوانست بخواند: " else "The service sent a stream this app could not read: "
    val ERR_TOO_LARGE: String get() = if (farsi) "پیام یا تاریخچهٔ گفتگو از حد مجاز سرویس بزرگ‌تر است. کوتاهش کنید یا گفتگوی تازه‌ای شروع کنید." else "The message or the conversation history is larger than the service accepts. Shorten it, or start a new chat."
    val ERR_UNPROCESSABLE: String get() = if (farsi) "سرویس نتوانست پارامترهای درخواست را پردازش کند. مدل و تنظیماتش را در تنظیمات بررسی کنید." else "The service could not process the request parameters. Check the model and its options in Settings."
    val ERR_NOT_READY: String get() = if (farsi) "سرویس هنوز آمادهٔ این درخواست نیست. کمی بعد دوباره تلاش کنید." else "The service is not ready to handle this request yet. Try again in a moment."
    val ERR_NO_CAPACITY: String get() = if (farsi) "ظرفیت سرویس موقتاً پر است." else "The service is temporarily out of capacity."
    val ERR_QUOTA: String get() = if (farsi) "سرویس خطای ۴۲۹ داد: سهمیه یا اعتبار حساب تمام شده است." else "The service returned HTTP 429: the account is out of quota or credit."
    val ERR_MODEL_BUSY: String get() = if (farsi) "سرویس خطای ۴۲۹ داد: ظرفیت مدل موقتاً پر است." else "The service returned HTTP 429: the model is temporarily out of capacity."
    val ERR_RATE_ACTIVE: String get() = if (farsi) "سرویس خطای ۴۲۹ داد: محدودیت نرخ درخواست یا توکن فعال است." else "The service returned HTTP 429: a request or token rate limit is in effect."
    val ERR_STOPPED_BY_YOU: String get() = if (farsi) "به درخواست شما متوقف شد." else "Stopped at your request."
    val ERR_TLS: String get() = if (farsi) "اتصال امن TLS برقرار نشد: " else "Could not establish a secure TLS connection: "
    val ERR_DETAILS_LINE: String get() = if (farsi) "\nجزئیات سرویس: " else "\nService details: "
    val ERR_RETRY_IN: String get() = if (farsi) "\nدوباره تلاش کنید در %s" else "\nTry again in %s"
    val ERR_REQ_ID: String get() = if (farsi) "\nشناسهٔ درخواست: " else "\nRequest ID: "
    val ERR_SECOND: String get() = if (farsi) "%s ثانیه" else "%s second"
    val ERR_SECONDS: String get() = if (farsi) "%s ثانیه" else "%s seconds"
    val ERR_PATH_OUTSIDE: String get() = if (farsi) "این مسیر بیرون از پوشه‌ای است که برنامه اجازهٔ استفاده از آن را دارد." else "That path is outside the folder this app may use."
    val ERR_PATH_BAD: String get() = if (farsi) "این مسیر فایل قابل تشخیص نبود" else "That file path could not be resolved"
    val ERR_PATH_PRIVATE: String get() = if (farsi) "دادهٔ خصوصی خود برنامه (کلیدها و تنظیمات) خارج از دسترس است: " else "The app's own private data (keys and settings) is off limits: "
    val ERR_CAPTCHA: String get() = if (farsi) "این صفحه پشت یک کپچای تعاملی است که فقط انسان می‌تواند از آن بگذرد. پیوند مستقیم محتوا یا سایت دیگری را امتحان کنید." else "This page is behind an interactive CAPTCHA, which only a person can clear. Try a direct link to the content, or another site."

    /**
     * Every rendering of [NEW_CHAT] this app has ever persisted.
     *
     * A chat's stored title is only a PLACEHOLDER until the first message names
     * it, and builds before v1 persisted the TRANSLATED text — so a transcript
     * written in Persian still carries the Persian placeholder on disk today.
     * Comparing against every known form keeps the auto-title check working on
     * those chats instead of leaving them stuck on a stale name.
     */
    private val PLACEHOLDER_TITLES = setOf("New chat", "\u06af\u0641\u062a\u06af\u0648\u06cc \u062c\u062f\u06cc\u062f")

    /** True when [title] is absent or still an unnamed placeholder. */
    fun isPlaceholderTitle(title: String?): Boolean {
        val t = title?.trimJava() ?: ""
        return t.isEmpty() || PLACEHOLDER_TITLES.contains(t)
    }

    /**
     * Every rendering of [RUN_STALLED] this app has ever persisted.
     *
     * The marker is written into the transcript as a real assistant turn, and
     * `lastTurnUnfinished()` tests for it to decide whether to offer the continue
     * card. Both pre-v1 translations are on disk in existing chats and v1
     * reworded it again, so every form has to match — otherwise the continue card
     * disappears from exactly the message that asks you to tap it.
     *
     * The two live forms are read from the getters, so rewording either one keeps
     * this set correct automatically.
     */
    private val LEGACY_STALLED = setOf(
        "It seems no response was generated for this step. If the task is unfinished, tap Continue to resume from here.",
        "\u0628\u0647 \u0646\u0638\u0631 \u0645\u06cc\u200c\u0631\u0633\u062f \u067e\u0627\u0633\u062e\u06cc \u0628\u0631\u0627\u06cc \u0627\u06cc\u0646 \u06af\u0627\u0645 \u062a\u0648\u0644\u06cc\u062f \u0646\u0634\u062f. \u0627\u06af\u0631 \u06a9\u0627\u0631 \u0646\u0627\u062a\u0645\u0627\u0645 \u0627\u0633\u062a\u060c \u00ab\u0627\u062f\u0627\u0645\u0647\u00bb \u0631\u0627 \u0628\u0632\u0646\u06cc\u062f \u062a\u0627 \u0627\u0632 \u0647\u0645\u06cc\u0646\u200c\u062c\u0627 \u0627\u062f\u0627\u0645\u0647 \u062f\u0647\u0645."
    )

    /** True when [content] is the "nothing was produced" marker, in any build's wording. */
    fun isStalledMessage(content: String?): Boolean {
        val t = content?.trimJava() ?: ""
        if (t.isEmpty()) {
            return false
        }
        // Both live renderings, not just the active one: the language may have
        // been switched since the run that wrote this.
        val live = farsi
        try {
            farsi = false
            if (t == RUN_STALLED) return true
            farsi = true
            if (t == RUN_STALLED) return true
        } finally {
            farsi = live
        }
        return LEGACY_STALLED.contains(t)
    }
}
