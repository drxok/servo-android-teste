/* Bloqueio de rastreadores e modo escuro das páginas (parte do nosso navegador). */

use std::cell::RefCell;
use std::rc::Rc;
use std::sync::atomic::{AtomicBool, AtomicI32, AtomicU32, Ordering};

use servo::WebResourceRequest;
use servo::user_contents::UserStyleSheet;
use servo::{Theme, UserContentManager, WebView};
use url::Url;

static ENABLED: AtomicBool = AtomicBool::new(true);
static BLOCKED: AtomicU32 = AtomicU32::new(0);
static REPORTED: AtomicU32 = AtomicU32::new(0);
/// 0 = claro, 1 = automático (sites com suporte), 2 = forçar em todos os sites
static DARK_MODE: AtomicI32 = AtomicI32::new(0);
static APPLIED_DARK_MODE: AtomicI32 = AtomicI32::new(0);

thread_local! {
    static FORCE_SHEET: RefCell<Option<Rc<UserStyleSheet>>> = const { RefCell::new(None) };
}

const FORCE_DARK_CSS: &str = "\
html { filter: invert(1) hue-rotate(180deg) !important; background: #fff !important; }\n\
img, video, canvas, picture, svg image, iframe { filter: invert(1) hue-rotate(180deg) !important; }\n";

pub fn set_enabled(enabled: bool) {
    ENABLED.store(enabled, Ordering::Relaxed);
}

pub fn set_dark_mode(mode: i32) {
    DARK_MODE.store(mode, Ordering::Relaxed);
}

/// Retorna a contagem se ela mudou desde a última vez que foi informada.
pub fn take_blocked_change() -> Option<u32> {
    let now = BLOCKED.load(Ordering::Relaxed);
    if REPORTED.swap(now, Ordering::Relaxed) != now {
        Some(now)
    } else {
        None
    }
}

/// Aplica o modo escuro das páginas se o pedido mudou.
pub fn apply_dark_mode(webview: &WebView, manager: &UserContentManager) {
    let wanted = DARK_MODE.load(Ordering::Relaxed);
    if APPLIED_DARK_MODE.swap(wanted, Ordering::Relaxed) == wanted {
        return;
    }
    webview.notify_theme_change(if wanted >= 1 { Theme::Dark } else { Theme::Light });
    FORCE_SHEET.with(|sheet| {
        let mut sheet = sheet.borrow_mut();
        if let Some(old) = sheet.take() {
            manager.remove_stylesheet(old);
        }
        if wanted == 2 {
            if let Ok(url) = Url::parse("servo://force-dark.css") {
                let new_sheet = Rc::new(UserStyleSheet::new(FORCE_DARK_CSS.to_string(), url));
                manager.add_stylesheet(new_sheet.clone());
                *sheet = Some(new_sheet);
            }
        }
    });
}

pub fn should_block(request: &WebResourceRequest) -> bool {
    if !ENABLED.load(Ordering::Relaxed) || request.is_for_main_frame {
        return false;
    }
    let Some(host) = request.url.as_url().host_str() else {
        return false;
    };
    if is_tracker_host(host) {
        BLOCKED.fetch_add(1, Ordering::Relaxed);
        true
    } else {
        false
    }
}

fn is_tracker_host(host: &str) -> bool {
    let host = host.trim_end_matches('.').to_ascii_lowercase();
    TRACKERS
        .iter()
        .any(|d| host == *d || host.ends_with(&format!(".{d}")))
}

const TRACKERS: &[&str] = &[
    "doubleclick.net",
    "googlesyndication.com",
    "googleadservices.com",
    "googletagmanager.com",
    "googletagservices.com",
    "google-analytics.com",
    "adservice.google.com",
    "pagead2.googlesyndication.com",
    "analytics.google.com",
    "app-measurement.com",
    "connect.facebook.net",
    "pixel.facebook.com",
    "an.facebook.com",
    "scorecardresearch.com",
    "quantserve.com",
    "quantcount.com",
    "taboola.com",
    "outbrain.com",
    "criteo.com",
    "criteo.net",
    "adnxs.com",
    "adsrvr.org",
    "rubiconproject.com",
    "pubmatic.com",
    "openx.net",
    "casalemedia.com",
    "amazon-adsystem.com",
    "advertising.com",
    "adform.net",
    "smartadserver.com",
    "moatads.com",
    "2mdn.net",
    "serving-sys.com",
    "bluekai.com",
    "demdex.net",
    "krxd.net",
    "everesttech.net",
    "omtrdc.net",
    "hotjar.com",
    "hotjar.io",
    "mouseflow.com",
    "fullstory.com",
    "crazyegg.com",
    "clarity.ms",
    "mixpanel.com",
    "segment.io",
    "segment.com",
    "amplitude.com",
    "heap.io",
    "heapanalytics.com",
    "newrelic.com",
    "nr-data.net",
    "chartbeat.com",
    "chartbeat.net",
    "parsely.com",
    "comscore.com",
    "sitemeter.com",
    "statcounter.com",
    "addthis.com",
    "sharethis.com",
    "disqusads.com",
    "yieldmo.com",
    "teads.tv",
    "inmobi.com",
    "applovin.com",
    "unityads.unity3d.com",
    "adcolony.com",
    "mopub.com",
    "vungle.com",
    "ads.twitter.com",
    "analytics.twitter.com",
    "ads-twitter.com",
    "static.ads-twitter.com",
    "ads.linkedin.com",
    "px.ads.linkedin.com",
    "snap.licdn.com",
    "analytics.tiktok.com",
    "ads.tiktok.com",
    "bat.bing.com",
    "ads.yahoo.com",
    "analytics.yahoo.com",
    "adtech.com",
    "adsafeprotected.com",
    "doubleverify.com",
    "iasds01.com",
    "tapad.com",
    "liadm.com",
    "rlcdn.com",
    "id5-sync.com",
    "3lift.com",
    "sharethrough.com",
    "triplelift.com",
    "gumgum.com",
    "media.net",
    "revcontent.com",
    "mgid.com",
    "zemanta.com",
    "yandex.ru/metrika",
    "mc.yandex.ru",
    "ad.gt",
    "adsymptotic.com",
    "tiqcdn.com",
    "branch.io",
    "adjust.com",
    "appsflyer.com",
    "kochava.com",
    "singular.net",
];
