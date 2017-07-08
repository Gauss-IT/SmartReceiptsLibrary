package co.smartreceipts.android.workers.reports.pdf.pdfbox;

import android.support.annotation.NonNull;
import android.text.TextUtils;

import com.google.common.base.Preconditions;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import co.smartreceipts.android.R;
import co.smartreceipts.android.filters.LegacyReceiptFilter;
import co.smartreceipts.android.model.Column;
import co.smartreceipts.android.model.Distance;
import co.smartreceipts.android.model.Receipt;
import co.smartreceipts.android.model.Trip;
import co.smartreceipts.android.model.comparators.ReceiptDateComparator;
import co.smartreceipts.android.model.converters.DistanceToReceiptsConverter;
import co.smartreceipts.android.persistence.database.controllers.grouping.results.CategoryGroupingResult;
import co.smartreceipts.android.persistence.database.controllers.grouping.results.SumCategoryGroupingResult;
import co.smartreceipts.android.purchases.model.InAppPurchase;
import co.smartreceipts.android.purchases.wallet.PurchaseWallet;
import co.smartreceipts.android.settings.UserPreferenceManager;
import co.smartreceipts.android.settings.catalog.UserPreference;
import co.smartreceipts.android.workers.reports.pdf.colors.PdfColorStyle;
import co.smartreceipts.android.workers.reports.pdf.fonts.PdfFontStyle;
import co.smartreceipts.android.workers.reports.pdf.renderer.empty.EmptyRenderer;
import co.smartreceipts.android.workers.reports.pdf.renderer.formatting.Alignment;
import co.smartreceipts.android.workers.reports.pdf.renderer.grid.GridRenderer;
import co.smartreceipts.android.workers.reports.pdf.renderer.grid.GridRowRenderer;
import co.smartreceipts.android.workers.reports.pdf.renderer.impl.PdfTableGenerator;
import co.smartreceipts.android.workers.reports.pdf.renderer.text.TextRenderer;

public class PdfBoxReceiptsTablePdfSection extends PdfBoxSection {

    private static final float EPSILON = 0.0001f;
    private static final int EMPTY_ROW_HEIGHT_NORMAL = 40;
    private static final int EMPTY_ROW_HEIGHT_SMALL = 10;


    private final List<Receipt> receipts;
    private final List<Column<Receipt>> receiptColumns;

    private final List<Distance> distances;
    private final List<Column<Distance>> distanceColumns;

    private final List<SumCategoryGroupingResult> categories;
    private final List<Column<SumCategoryGroupingResult>> categoryColumns;

    private final List<CategoryGroupingResult> groupingResults;

    private final UserPreferenceManager preferenceManager;
    private final PurchaseWallet purchaseWallet;

    private PdfBoxWriter writer;

    protected PdfBoxReceiptsTablePdfSection(@NonNull PdfBoxContext context,
                                            @NonNull Trip trip,
                                            @NonNull List<Receipt> receipts,
                                            @NonNull List<Column<Receipt>> receiptColumns,
                                            @NonNull List<Distance> distances,
                                            @NonNull List<Column<Distance>> distanceColumns,
                                            @NonNull List<SumCategoryGroupingResult> categories,
                                            @NonNull List<Column<SumCategoryGroupingResult>> categoryColumns,
                                            @NonNull List<CategoryGroupingResult> groupingResults,
                                            @NonNull PurchaseWallet purchaseWallet) {
        super(context, trip);
        this.receipts = Preconditions.checkNotNull(receipts);
        this.distances = Preconditions.checkNotNull(distances);
        this.categories = Preconditions.checkNotNull(categories);
        this.groupingResults = Preconditions.checkNotNull(groupingResults);
        this.receiptColumns = Preconditions.checkNotNull(receiptColumns);
        this.distanceColumns = Preconditions.checkNotNull(distanceColumns);
        this.categoryColumns = Preconditions.checkNotNull(categoryColumns);
        preferenceManager = Preconditions.checkNotNull(context.getPreferences());
        this.purchaseWallet = Preconditions.checkNotNull(purchaseWallet);
    }


    @Override
    public void writeSection(@NonNull PDDocument doc, @NonNull PdfBoxWriter writer) throws IOException {

        final DefaultPdfBoxPageDecorations pageDecorations = new DefaultPdfBoxPageDecorations(pdfBoxContext, trip);
        final ReceiptsTotals totals = new ReceiptsTotals(trip, receipts, distances, preferenceManager);

        // switch to landscape mode
        if (preferenceManager.get(UserPreference.ReportOutput.PrintReceiptsTableInLandscape)) {
            pdfBoxContext.setPageSize(new PDRectangle(pdfBoxContext.getPageSize().getHeight(),
                    pdfBoxContext.getPageSize().getWidth()));
        }

        this.writer = writer;
        this.writer.newPage();

        final float availableWidth = pdfBoxContext.getPageSize().getWidth() - 2 * pdfBoxContext.getPageMarginHorizontal();
        final float availableHeight = pdfBoxContext.getPageSize().getHeight() - 2 * pdfBoxContext.getPageMarginVertical()
                - pageDecorations.getHeaderHeight() - pageDecorations.getFooterHeight();

        final GridRenderer gridRenderer = new GridRenderer(availableWidth, availableHeight);
        gridRenderer.addRows(writeHeader(trip, doc, totals));

        if (!receipts.isEmpty() &&
                (!purchaseWallet.hasActivePurchase(InAppPurchase.SmartReceiptsPlus) ||
                        (purchaseWallet.hasActivePurchase(InAppPurchase.SmartReceiptsPlus) &&
                                !preferenceManager.get(UserPreference.PlusSubscription.OmitDefaultTableInReports)))) {
            gridRenderer.addRow(new GridRowRenderer(new EmptyRenderer(0, EMPTY_ROW_HEIGHT_NORMAL)));
            gridRenderer.addRows(writeReceiptsTable(receipts, doc));
        }

        if (preferenceManager.get(UserPreference.Distance.PrintDistanceTableInReports) && !distances.isEmpty()) {
            gridRenderer.addRow(new GridRowRenderer(new EmptyRenderer(0, EMPTY_ROW_HEIGHT_NORMAL)));
            gridRenderer.addRows(writeDistancesTable(distances, doc));
        }

        if (purchaseWallet.hasActivePurchase(InAppPurchase.SmartReceiptsPlus) &&
                preferenceManager.get(UserPreference.PlusSubscription.CategoricalSummationInReports)
                && !categories.isEmpty()) {
            gridRenderer.addRow(new GridRowRenderer(new EmptyRenderer(0, EMPTY_ROW_HEIGHT_NORMAL)));
            gridRenderer.addRows(writeCategoriesTable(categories, doc));
        }

        if (purchaseWallet.hasActivePurchase(InAppPurchase.SmartReceiptsPlus) &&
                preferenceManager.get(UserPreference.PlusSubscription.SeparateByCategoryInReports)
                && !groupingResults.isEmpty()) {

            for (CategoryGroupingResult groupingResult : groupingResults) {
                gridRenderer.addRow(new GridRowRenderer(new EmptyRenderer(0, EMPTY_ROW_HEIGHT_NORMAL)));

                GridRowRenderer groupTitleRenderer = new GridRowRenderer(new TextRenderer(
                        pdfBoxContext.getAndroidContext(),
                        doc,
                        groupingResult.getCategory().getName(),
                        pdfBoxContext.getColorManager().getColor(PdfColorStyle.Outline),
                        pdfBoxContext.getFontManager().getFont(PdfFontStyle.TableHeader)));

                groupTitleRenderer.getRenderingFormatting().addFormatting(new Alignment(Alignment.Type.Start));

                GridRowRenderer paddingRenderer = new GridRowRenderer(new EmptyRenderer(0, EMPTY_ROW_HEIGHT_SMALL));

                gridRenderer.addRow(groupTitleRenderer);
                gridRenderer.addRow(paddingRenderer);
                gridRenderer.addRows(writeSeparateCategoryTable(groupingResult.getReceipts(), doc));
            }
        }

        gridRenderer.measure();
        gridRenderer.render(this.writer);

        // reset the page size if necessary
        if (preferenceManager.get(UserPreference.ReportOutput.PrintReceiptsTableInLandscape)) {
            pdfBoxContext.setPageSize(new PDRectangle(pdfBoxContext.getPageSize().getHeight(),
                    pdfBoxContext.getPageSize().getWidth()));
        }
    }

    private List<GridRowRenderer> writeHeader(@NonNull Trip trip, @NonNull PDDocument pdDocument, @NonNull ReceiptsTotals data) throws IOException {

        final List<GridRowRenderer> headerRows = new ArrayList<>();
        headerRows.add(new GridRowRenderer(new TextRenderer(
                pdfBoxContext.getAndroidContext(),
                pdDocument,
                trip.getName(),
                pdfBoxContext.getColorManager().getColor(PdfColorStyle.Default),
                pdfBoxContext.getFontManager().getFont(PdfFontStyle.Title))));

        if (!data.receiptsPrice.equals(data.netPrice)) {
            headerRows.add(new GridRowRenderer(new TextRenderer(
                    pdfBoxContext.getAndroidContext(),
                    pdDocument,
                    pdfBoxContext.getAndroidContext().getString(R.string.report_header_receipts_total, data.receiptsPrice.getCurrencyFormattedPrice()),
                    pdfBoxContext.getColorManager().getColor(PdfColorStyle.Default),
                    pdfBoxContext.getFontManager().getFont(PdfFontStyle.Default))));
        }

        if (preferenceManager.get(UserPreference.Receipts.IncludeTaxField)) {
            if (preferenceManager.get(UserPreference.Receipts.UsePreTaxPrice) && data.taxPrice.getPriceAsFloat() > EPSILON) {
                headerRows.add(new GridRowRenderer(new TextRenderer(
                        pdfBoxContext.getAndroidContext(),
                        pdDocument,
                        pdfBoxContext.getAndroidContext().getString(R.string.report_header_receipts_total_tax, data.taxPrice.getCurrencyFormattedPrice()),
                        pdfBoxContext.getColorManager().getColor(PdfColorStyle.Default),
                        pdfBoxContext.getFontManager().getFont(PdfFontStyle.Default))));
            } else if (!data.noTaxPrice.equals(data.receiptsPrice) && data.noTaxPrice.getPriceAsFloat() > EPSILON) {
                headerRows.add(new GridRowRenderer(new TextRenderer(
                        pdfBoxContext.getAndroidContext(),
                        pdDocument,
                        pdfBoxContext.getAndroidContext().getString(R.string.report_header_receipts_total_no_tax, data.noTaxPrice.getCurrencyFormattedPrice()),
                        pdfBoxContext.getColorManager().getColor(PdfColorStyle.Default),
                        pdfBoxContext.getFontManager().getFont(PdfFontStyle.Default))));
            }
        }

        if (!preferenceManager.get(UserPreference.Receipts.OnlyIncludeReimbursable) && !data.reimbursablePrice.equals(data.receiptsPrice)) {
            headerRows.add(new GridRowRenderer(new TextRenderer(
                    pdfBoxContext.getAndroidContext(),
                    pdDocument,
                    pdfBoxContext.getAndroidContext().getString(R.string.report_header_receipts_total_reimbursable, data.reimbursablePrice.getCurrencyFormattedPrice()),
                    pdfBoxContext.getColorManager().getColor(PdfColorStyle.Default),
                    pdfBoxContext.getFontManager().getFont(PdfFontStyle.Default))));
        }
        if (!distances.isEmpty()) {
            headerRows.add(new GridRowRenderer(new TextRenderer(
                    pdfBoxContext.getAndroidContext(),
                    pdDocument,
                    pdfBoxContext.getAndroidContext().getString(R.string.report_header_distance_total, data.distancePrice.getCurrencyFormattedPrice()),
                    pdfBoxContext.getColorManager().getColor(PdfColorStyle.Default),
                    pdfBoxContext.getFontManager().getFont(PdfFontStyle.Default))));
        }

        headerRows.add(new GridRowRenderer(new TextRenderer(
                pdfBoxContext.getAndroidContext(),
                pdDocument,
                pdfBoxContext.getAndroidContext().getString(R.string.report_header_gross_total, data.netPrice.getCurrencyFormattedPrice()),
                pdfBoxContext.getColorManager().getColor(PdfColorStyle.Default),
                pdfBoxContext.getFontManager().getFont(PdfFontStyle.Default))));

        String fromToPeriod = pdfBoxContext.getString(R.string.report_header_from,
                trip.getFormattedStartDate(pdfBoxContext.getAndroidContext(), preferenceManager.get(UserPreference.General.DateSeparator)))
                + " "
                + pdfBoxContext.getString(R.string.report_header_to,
                trip.getFormattedEndDate(pdfBoxContext.getAndroidContext(), preferenceManager.get(UserPreference.General.DateSeparator)));

        headerRows.add(new GridRowRenderer(new TextRenderer(
                pdfBoxContext.getAndroidContext(),
                pdDocument,
                fromToPeriod,
                pdfBoxContext.getColorManager().getColor(PdfColorStyle.Default),
                pdfBoxContext.getFontManager().getFont(PdfFontStyle.Default))));


        if (preferenceManager.get(UserPreference.General.IncludeCostCenter) && !TextUtils.isEmpty(trip.getCostCenter())) {
            headerRows.add(new GridRowRenderer(new TextRenderer(
                    pdfBoxContext.getAndroidContext(),
                    pdDocument,
                    pdfBoxContext.getAndroidContext().getString(R.string.report_header_cost_center, trip.getCostCenter()),
                    pdfBoxContext.getColorManager().getColor(PdfColorStyle.Default),
                    pdfBoxContext.getFontManager().getFont(PdfFontStyle.Default))));
        }
        if (!TextUtils.isEmpty(trip.getComment())) {
            headerRows.add(new GridRowRenderer(new TextRenderer(
                    pdfBoxContext.getAndroidContext(),
                    pdDocument,
                    pdfBoxContext.getAndroidContext().getString(R.string.report_header_comment, trip.getComment()),
                    pdfBoxContext.getColorManager().getColor(PdfColorStyle.Default),
                    pdfBoxContext.getFontManager().getFont(PdfFontStyle.Default))));
        }

        for (final GridRowRenderer headerRow : headerRows) {
            headerRow.getRenderingFormatting().addFormatting(new Alignment(Alignment.Type.Start));
        }
        return headerRows;
    }

    private List<GridRowRenderer> writeReceiptsTable(@NonNull List<Receipt> receipts, @NonNull PDDocument pdDocument) throws IOException {

        final List<Receipt> receiptsTableList = new ArrayList<>(receipts);
        if (preferenceManager.get(UserPreference.Distance.PrintDistanceAsDailyReceiptInReports)) {
            receiptsTableList.addAll(new DistanceToReceiptsConverter(pdfBoxContext.getAndroidContext(), preferenceManager).convert(distances));
            Collections.sort(receiptsTableList, new ReceiptDateComparator());
        }

        final PdfTableGenerator<Receipt> pdfTableGenerator = new PdfTableGenerator<>(pdfBoxContext, receiptColumns,
                pdDocument, new LegacyReceiptFilter(preferenceManager), true, true);

        return pdfTableGenerator.generate(receiptsTableList);
    }

    private List<GridRowRenderer> writeDistancesTable(@NonNull List<Distance> distances, @NonNull PDDocument pdDocument) throws IOException {
        final PdfTableGenerator<Distance> pdfTableGenerator = new PdfTableGenerator<>(pdfBoxContext, distanceColumns,
                pdDocument, null, true, true);
        return pdfTableGenerator.generate(distances);
    }

    private List<GridRowRenderer> writeCategoriesTable(@NonNull List<SumCategoryGroupingResult> categories, @NonNull PDDocument pdDocument) throws IOException {

        final PdfTableGenerator<SumCategoryGroupingResult> pdfTableGenerator = new PdfTableGenerator<>(pdfBoxContext, categoryColumns,
                pdDocument, null, true, true);

        return pdfTableGenerator.generate(categories);
    }

    private List<GridRowRenderer> writeSeparateCategoryTable(@NonNull List<Receipt> receipts, @NonNull PDDocument pdDocument) throws IOException {

        final PdfTableGenerator<Receipt> pdfTableGenerator = new PdfTableGenerator<>(pdfBoxContext, receiptColumns,
                pdDocument, null, true, true);

        return pdfTableGenerator.generate(receipts);
    }

}
