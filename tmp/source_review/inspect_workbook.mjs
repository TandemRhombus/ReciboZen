import { FileBlob, SpreadsheetFile } from "@oai/artifact-tool";

const source = "C:/Users/emili/Downloads/Base_CFE_Ahorro_Energia_App.xlsx";
const workbook = await SpreadsheetFile.importXlsx(await FileBlob.load(source));

const overview = await workbook.inspect({
  kind: "workbook,sheet,table",
  maxChars: 12000,
  tableMaxRows: 12,
  tableMaxCols: 12,
  tableMaxCellChars: 120,
});
console.log(overview.ndjson);

const dacMatches = await workbook.inspect({
  kind: "match",
  searchTerm: "DAC|alto consumo|límite|limite|promedio",
  options: { useRegex: true, maxResults: 200 },
  maxChars: 16000,
  summary: "DAC-related source rows",
});
console.log(dacMatches.ndjson);
