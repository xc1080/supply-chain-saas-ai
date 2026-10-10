"""Real local ERP XLSX import: same-name variants, frozen identity and concurrent code creation."""
from concurrent.futures import ThreadPoolExecutor
from contextlib import ExitStack
import io
import json
from pathlib import Path
import uuid
import xml.etree.ElementTree as ET
import zipfile
from urllib.parse import urlparse
from smoke_supply_execution import Acceptance, JAVA, SafeFailure

NS = "{http://schemas.openxmlformats.org/spreadsheetml/2006/main}"
FIELDS = {"货品编号": "productCode", "货品名称": "productName", "货品类型": "productType",
          "商品规格": "productSpecifications", "计量单位": "measureUnit", "参考售价": "univalence",
          "成本价": "costPrice", "销售折扣": "discount", "库存上限": "upperLimit",
          "库存下限": "lowerLimit", "默认仓库": "defaultWarehouse", "备注": "notes"}


def workbook(template, rows):
    # Reuse the application's actual workbook/header contract, not a parallel Excel schema.
    with zipfile.ZipFile(io.BytesIO(template)) as archive:
        entries = {info.filename: archive.read(info) for info in archive.infolist()}
    strings = ET.fromstring(entries.get("xl/sharedStrings.xml", b'<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"/>'))
    labels = ["".join(si.itertext()) for si in strings]
    sheet = ET.fromstring(entries["xl/worksheets/sheet1.xml"])
    data = sheet.find(NS + "sheetData")
    header = data.find(NS + "row")
    columns = {}
    for cell in header.findall(NS + "c"):
        text = labels[int(cell.find(NS + "v").text)] if cell.get("t") == "s" else "".join(cell.itertext())
        columns[cell.get("r").rstrip("0123456789")] = text
    for row in list(data):
        if row is not header: data.remove(row)
    for index, product in enumerate(rows, start=2):
        row = ET.SubElement(data, NS + "row", {"r": str(index)})
        for column, label in columns.items():
            value = "正常" if label == "帐号状态" else product.get(FIELDS.get(label), "")
            if value in (None, ""): continue
            cell = ET.SubElement(row, NS + "c", {"r": column + str(index), "t": "inlineStr"})
            ET.SubElement(ET.SubElement(cell, NS + "is"), NS + "t").text = str(value)
    entries["xl/worksheets/sheet1.xml"] = ET.tostring(sheet, encoding="utf-8", xml_declaration=True)
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as archive:
        for name, content in entries.items(): archive.writestr(name, content)
    return output.getvalue()


def run():
    if urlparse(JAVA).hostname not in {"127.0.0.1", "localhost", "::1"}:
        raise SafeFailure("Import acceptance is limited to local Java")
    root = Path(__file__).resolve().parent.parent
    scenario = json.loads((root / "logs/sku-catalog-result.json").read_text(encoding="utf-8"))
    check = Acceptance()
    check.report.update(kind="MYSQL_LIVE_SKU_IMPORT", existingSkuChanged=False)
    with ExitStack() as stack:
        admin = check.merchant(stack, "admin")
        ids = [scenario["state"]["whiteProductId"], scenario["state"]["blackProductId"]]
        products = [check.api(admin, "ownedSku." + str(pid), "GET", f"/baseDate/product/{pid}") for pid in ids]
        check.check("onlyOwnedScenarioProducts", all(p["productCode"].startswith("LAB-SKU-" + scenario["runId"].upper()) for p in products))
        template = admin.post("/baseDate/product/importTemplate").content
        check.check("actualExcelTemplate", template.startswith(b"PK"))
        def import_rows(rows, update):
            response = admin.post("/baseDate/product/importData", params={"updateSupport": str(update).lower()},
                                  files={"file": ("owned-skus.xlsx", workbook(template, rows), "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")})
            return response.json()
        imported = import_rows(products, True)
        check.check("sameNameDifferentCodesImportSucceeds", imported["code"] == 200)
        reread = [check.api(admin, "reread." + str(pid), "GET", f"/baseDate/product/{pid}") for pid in ids]
        check.check("differentSkuPricesAndSpecsNotOverwritten", all(
            all(str(old[field]) == str(new[field]) for field in ("productCode", "productSpecifications", "univalence"))
            for old, new in zip(products, reread)))
        # First row would change a price; the second changes a frozen SKU specification.
        invalid = [{**products[0], "univalence": "97.00"}, {**products[1], "productSpecifications": "WRONG_IDENTITY"}]
        rejected = import_rows(invalid, True)
        check.check("frozenIdentityRejectedWithConflict", rejected["code"] == 409)
        reread = check.api(admin, "rollbackPrice", "GET", f"/baseDate/product/{ids[0]}")
        check.check("wholeImportRollsBackBeforeRejectedRow", str(reread["univalence"]) == str(products[0]["univalence"]))
        code = "LAB-SKU-IMPORT-" + uuid.uuid4().hex[:12].upper()
        fresh = {**products[0], "productCode": code, "productName": "并发编码学习样例",
                 "productSpecifications": "未分组/虚构", "univalence": "11"}
        with ThreadPoolExecutor(max_workers=2) as pool:
            results = list(pool.map(lambda _: import_rows([fresh], False), range(2)))
        check.check("concurrentImportExactlyOneSucceeds", sum(row["code"] == 200 for row in results) == 1)
        check.check("duplicateImportConflict", sorted(row["code"] for row in results) == [200, 409])
        rows = check.request(admin, "ownedConcurrentCode", "GET", "/baseDate/product/list?productCode=" + code)["rows"]
        check.check("exactlyOnePhysicalSkuForCode", len(rows) == 1 and rows[0]["productCode"] == code)
        check.report.update(passed=True, concurrentCodes=[row["code"] for row in results],
                            ownedImportProductId=rows[0]["productId"])
    (root / "logs/sku-import-result.json").write_text(json.dumps(check.report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"passed": True, "checks": len(check.report["checks"]), "concurrentCodes": check.report["concurrentCodes"]}))


if __name__ == "__main__": run()
