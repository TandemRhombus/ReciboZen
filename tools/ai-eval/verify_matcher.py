#!/usr/bin/env python3
"""Corre la lógica nueva del buscador (CatalogQuery.kt + EnergyStarCatalog.kt)
contra el catálogo real de 9,305 modelos, con las mismas 30 consultas del
diagnóstico. Es un puerto fiel del Kotlin: si algo aquí no cuadra, el Kotlin
tampoco cuadra.

Uso:  python3 tools/ai-eval/verify_matcher.py
"""
import gzip
import json
import os
import sys
import unicodedata

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
CATALOG = os.path.join(ROOT, "app/src/main/assets/catalog/energy_star_models_v1.jsonl.gzip")


def norm(s):
    s = unicodedata.normalize("NFD", s)
    s = "".join(c for c in s if unicodedata.category(c) != "Mn")
    return " ".join("".join(c if c.isalnum() else " " for c in s.lower()).split())


def tokens(s):
    return norm(s).split()


# --- ProductKind ------------------------------------------------------------
KIND_SUBCATS = {
    "refrigerador": {"refrigerador_residencial"},
    "congelador": {"congelador_residencial"},
    "aire acondicionado": {"aire_acondicionado_ventana_portatil"},
    "televisor": {"televisor"},
    "computadora": {"computadora"},
    "lavadora": {"lavadora_residencial"},
    "lavavajillas": {"lavavajillas_residencial"},
    "calentador de agua": {"calentador_agua"},
}
KIND_WORDS = {}
for kind, words in {
    "refrigerador": "refrigerador refri refrigerator nevera heladera frigorifico frigobar",
    "congelador": "congelador freezer conservador",
    "aire acondicionado": "aire acondicionado clima minisplit split inverter climatizacion",
    "televisor": "televisor television tele tv pantalla",
    "computadora": "computadora compu computer pc laptop portatil notebook desktop",
    "lavadora": "lavadora washer washing",
    "lavavajillas": "lavavajillas lavaplatos lavatrastes dishwasher",
    "calentador de agua": "calentador boiler boyler calenton calentadores",
}.items():
    for w in words.split():
        KIND_WORDS[w] = kind

MX_BRANDS_WITHOUT_DATA = {
    "mabe": "Mabe", "acros": "Acros", "iem": "IEM", "koblenz": "Koblenz",
    "winia": "Winia", "daewoo": "Daewoo", "mirage": "Mirage", "atvio": "Atvio",
    "easy": "Easy", "hisense": "Hisense", "tcl": "TCL",
}

NEVER_A_BRAND = set("""horno hornito microondas licuadora batidora plancha ventilador
estufa parrilla secadora bomba tostador cafetera aspiradora extractor campana freidora
olla sarten purificador deshumidificador humidificador calefactor motobomba hidroneumatico
alberca casa cocina sala cuarto recamara bano patio garage techo grande chico pequeno
mediano nuevo viejo gamer gaming puertas puerta pulgadas pulgada litros pies btu watts
kwh electrico electrica gas solar de la el un una con sin para mi que por del y o""".split())

MIN_FUZZY_LENGTH = 5


def edit_distance_at_most(a, b, limit):
    if a == b:
        return 0
    if abs(len(a) - len(b)) > limit:
        return limit + 1
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb)))
        if min(cur) > limit:
            return limit + 1
        prev = cur
    return prev[-1]


class Parser:
    def __init__(self, brands):
        counts = {}
        for b in brands:
            n = norm(b)
            if n:
                counts.setdefault(n, {}).setdefault(b.strip(), 0)
                counts[n][b.strip()] += 1
        self.canonical = {n: max(v.items(), key=lambda kv: kv[1])[0] for n, v in counts.items()}
        self.normalized = list(self.canonical)

    def resolve_brand(self, tok, allow_fuzzy):
        if tok in self.canonical:
            return self.canonical[tok], False
        if tok in MX_BRANDS_WITHOUT_DATA:
            return MX_BRANDS_WITHOUT_DATA[tok], True
        if not allow_fuzzy:
            return None
        if any(c.isdigit() for c in tok) or len(tok) < MIN_FUZZY_LENGTH:
            return None
        budget = 2 if len(tok) >= 8 else 1
        best, bd = None, budget + 1
        for cand in self.normalized:
            d = edit_distance_at_most(tok, cand, budget)
            if d < bd:
                bd, best = d, cand
        if best is not None and bd <= budget:
            return self.canonical[best], False
        return None

    def parse(self, raw):
        toks = tokens(raw)
        kind_words = {t for t in toks if t in KIND_WORDS}
        kinds = {KIND_WORDS[t] for t in kind_words}
        def is_model(t):
            return any(c.isdigit() for c in t) and (any(not c.isdigit() for c in t) or len(t) >= 3)

        allow_fuzzy = len(toks) == 1 or bool(kinds) or any(is_model(t) for t in toks)
        brand = brand_typed = None
        for t in toks:
            if t in kind_words or t in NEVER_A_BRAND:
                continue
            r = self.resolve_brand(t, allow_fuzzy)
            if r:
                brand, brand_typed = r[0], t
                break
        consumed = set(kind_words) | ({brand_typed} if brand_typed else set())
        rest = [t for t in toks if t not in consumed]
        return {
            "tokens": toks, "brand": brand, "kinds": kinds,
            "model_tokens": [t for t in rest if is_model(t)],
            "other_tokens": [t for t in rest if not is_model(t)],
        }


def main():
    if not os.path.exists(CATALOG):
        sys.exit(f"No encontré el catálogo en {CATALOG}")
    records = [json.loads(l) for l in gzip.open(CATALOG, "rt", encoding="utf-8")]
    for r in records:
        r["_brand"] = norm(r["brand"])
        r["_model"] = norm(r["model"])
        r["_searchable"] = norm(f"{r['brand']} {r['model']} {r['subcategory'].replace('_', ' ')}")
    parser = Parser([r["brand"] for r in records])
    brands_present = {r["_brand"] for r in records}

    def collect(p, require_model):
        out = []
        for r in records:
            if p["brand"] and r["_brand"] != norm(p["brand"]):
                continue
            if p["kinds"] and not any(r["subcategory"] in KIND_SUBCATS[k] for k in p["kinds"]):
                continue
            if require_model and not any(t in r["_model"] for t in p["model_tokens"]):
                continue
            anchored = bool(p["brand"]) or bool(p["kinds"])
            if not anchored:
                if all(t in r["_searchable"] for t in p["tokens"]):
                    out.append(r)
                continue
            out.append(r)
        return out

    def search(q):
        p = parser.parse(q)
        if not p["tokens"]:
            return ("NOT_ENOUGH", None, [])
        out = collect(p, bool(p["model_tokens"])) or collect(p, False)
        if out:
            return ("MATCHES", p["brand"], out)
        if p["brand"] and norm(p["brand"]) not in brands_present:
            return ("BRAND_NOT_COVERED", p["brand"], [])
        return ("NO_MATCHES", p["brand"], [])

    def old_search(q):
        t = tokens(q)
        return [r for r in records if t and all(tok in r["_searchable"] for tok in t)]

    CASOS = [
        "Samsung BRB106003**", "BRB106", "refrigerador samsung", "refri lg",
        "Mabe RMA0821XMXGC", "Mabe RMA1025", "Acros AT1330G", "IEM refrigerador",
        "Winia DFR-25210", "Koblenz ventilador", "aire acondicionado mirage",
        "minisplit lg inverter 12000", "PlayStation 5", "Xbox Series X", "Nintendo Switch 2",
        "pantalla lg 55 pulgadas", "televisión samsung", "samsug rf28", "whirpool",
        "laptop hp", "boiler eléctrico", "bomba de agua", "licuadora", "plancha",
        "horno de microondas", "ventilador de techo", "Whirlpool lavadora", "frigidaire",
        "LG 43QNED80AU*", "computadora gamer",
    ]
    # Marca que el usuario pidió; None = no pidió marca.
    EXPECTED_BRAND = {
        "Mabe RMA1025": "mabe", "Mabe RMA0821XMXGC": "mabe", "Acros AT1330G": "acros",
        "IEM refrigerador": "iem", "Winia DFR-25210": "winia", "Koblenz ventilador": "koblenz",
        "aire acondicionado mirage": "mirage", "whirpool": "whirlpool", "samsug rf28": "samsung",
        "laptop hp": "hp", "pantalla lg 55 pulgadas": "lg", "televisión samsung": "samsung",
        "minisplit lg inverter 12000": "lg", "refrigerador samsung": "samsung", "refri lg": "lg",
        "Samsung BRB106003**": "samsung", "Whirlpool lavadora": "whirlpool",
        "frigidaire": "frigidaire", "LG 43QNED80AU*": "lg",
    }

    print(f"{'CONSULTA':32}{'ANTES':>7}{'AHORA':>7}  VEREDICTO")
    print("-" * 92)
    utiles = falsos = honestos = vacios = 0
    for q in CASOS:
        old_n = len(old_search(q))
        kind, brand, res = search(q)
        want = EXPECTED_BRAND.get(q)
        if kind == "BRAND_NOT_COVERED":
            verdict = f"HONESTO: dice «todavía no tenemos {brand}»"
            honestos += 1
        elif kind in ("NO_MATCHES", "NOT_ENOUGH"):
            verdict = "sin resultados"
            vacios += 1
        elif want:
            ok = sum(1 for r in res if want in r["_brand"])
            if ok:
                verdict = f"ÚTIL: {len(res)} resultados, {ok} son {want}"
                utiles += 1
            else:
                verdict = f"FALSO POSITIVO: {len(res)}, ninguno es {want}"
                falsos += 1
        else:
            verdict = f"{len(res)} resultados sin marca pedida"
            utiles += 1
        print(f"{q[:31]:32}{old_n:>7}{len(res):>7}  {verdict}")

    print("-" * 92)
    print(f"ÚTILES: {utiles} · FALSOS POSITIVOS: {falsos} · "
          f"«no tenemos esa marca»: {honestos} · sin resultados: {vacios}")
    print("\nEl número que importa no es cuántas consultas devuelven algo, sino")
    print("cuántas devuelven la marca equivocada. Ese tiene que ser cero.")
    return 1 if falsos else 0


if __name__ == "__main__":
    sys.exit(main())
