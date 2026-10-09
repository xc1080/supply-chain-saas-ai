"""Local illustrative media; never a claim of a manufacturer's product photo."""
from business_catalog import profile_for

IMAGES = {
    "DEMO-LAMP-ZB": "lamp-zb", "DEMO-LAMP-WIFI": "lamp-wifi", "DEMO-LAMP-PRO": "lamp-pro",
    "DEMO-SENSOR-DOOR": "sensor-door", "DEMO-SENSOR-MOTION": "sensor-motion",
    "DEMO-GATEWAY-ZB": "gateway-zb", "DEMO-LOCK-WIFI": "lock-wifi", "DEMO-SWITCH-ZB": "switch-zb",
}
PROFILE_IMAGES = {"lamp": "lamp-wifi", "gateway": "gateway-zb", "sensor": "sensor-door", "switch": "switch-zb", "plug": "switch-zb"}


def product_cover(code, source=None, profile=None):
    if source and source not in ("/media/demo/fallback.svg", "/demo-media/fallback.svg",
                                 "/media/demo/products/fallback.svg", "/demo-media/products/fallback.svg"):
        return source
    profile = profile or profile_for(code) or {}
    image = "sensor-motion" if code == "LAB-TAPO-T100" else IMAGES.get(code) or PROFILE_IMAGES.get(profile.get("imageKind"))
    return f"/media/demo/products/{image}.svg" if image else "/media/demo/fallback.svg"
