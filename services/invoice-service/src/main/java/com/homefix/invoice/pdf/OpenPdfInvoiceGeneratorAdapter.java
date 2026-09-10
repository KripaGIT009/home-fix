package com.homefix.invoice.pdf;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import com.homefix.invoice.domain.InvoiceData;
import com.homefix.invoice.domain.PriceLineItem;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;

import org.springframework.stereotype.Component;

/**
 * Default {@link PdfGeneratorPort} adapter backed by OpenPDF. Renders every field required by
 * Requirement 13.1: invoice number and date, customer name/address, provider name/verified
 * status, service description, the labeled itemized price breakdown, tax amount and tax
 * identifier, discount amount (omitted when zero), the final total, and the payment method.
 *
 * <p>Active only when no other {@link PdfGeneratorPort} bean is present (tests supply a fake or a
 * deliberately failing generator).
 */
@Component
public class OpenPdfInvoiceGeneratorAdapter implements PdfGeneratorPort {

    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    private static final Font TITLE_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 18);
    private static final Font LABEL_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10);
    private static final Font BODY_FONT = FontFactory.getFont(FontFactory.HELVETICA, 10);

    @Override
    public byte[] render(InvoiceData data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document document = new Document();
        try {
            PdfWriter.getInstance(document, out);
            document.open();

            document.add(new Paragraph("HomeFix Tax Invoice", TITLE_FONT));
            document.add(new Paragraph(" ", BODY_FONT));

            document.add(labeled("Invoice Number", data.invoiceNumber()));
            document.add(labeled("Invoice Date", DATE.format(data.invoiceDate())));
            document.add(new Paragraph(" ", BODY_FONT));

            document.add(labeled("Billed To", data.customerName()));
            document.add(labeled("Address", data.customerAddress()));
            document.add(new Paragraph(" ", BODY_FONT));

            String providerLine = data.providerName()
                    + (data.providerVerified() ? " (Verified)" : " (Unverified)");
            document.add(labeled("Service Provider", providerLine));
            document.add(labeled("Service", data.serviceDescription()));
            document.add(new Paragraph(" ", BODY_FONT));

            document.add(buildBreakdownTable(data));

            document.add(new Paragraph(" ", BODY_FONT));
            document.add(labeled("Payment Method", data.paymentMethod()));

            document.close();
        } catch (RuntimeException | Error rendering) {
            safeClose(document);
            throw new InvoicePdfException("Failed to render invoice PDF for " + data.invoiceNumber(), rendering);
        }
        return out.toByteArray();
    }

    private PdfPTable buildBreakdownTable(InvoiceData data) {
        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(100);

        table.addCell(headerCell("Description"));
        table.addCell(headerCell("Amount"));

        for (PriceLineItem item : data.lineItems()) {
            table.addCell(bodyCell(item.label(), Element.ALIGN_LEFT));
            table.addCell(bodyCell(money(item.amount()), Element.ALIGN_RIGHT));
        }

        table.addCell(bodyCell("Tax (" + data.taxIdentifier() + ")", Element.ALIGN_LEFT));
        table.addCell(bodyCell(money(data.taxAmount()), Element.ALIGN_RIGHT));

        // Discount is rendered only when non-zero (Requirement 13.1).
        if (data.hasDiscount()) {
            table.addCell(bodyCell("Discount", Element.ALIGN_LEFT));
            table.addCell(bodyCell(money(data.discountAmount().abs().negate()), Element.ALIGN_RIGHT));
        }

        table.addCell(totalCell("Total"));
        table.addCell(totalCell(money(data.totalAmount())));
        return table;
    }

    private static Paragraph labeled(String label, String value) {
        Paragraph p = new Paragraph();
        p.add(new Phrase(label + ": ", LABEL_FONT));
        p.add(new Phrase(value == null ? "" : value, BODY_FONT));
        return p;
    }

    private static PdfPCell headerCell(String text) {
        PdfPCell cell = new PdfPCell(new Phrase(text, LABEL_FONT));
        cell.setHorizontalAlignment(Element.ALIGN_LEFT);
        return cell;
    }

    private static PdfPCell bodyCell(String text, int alignment) {
        PdfPCell cell = new PdfPCell(new Phrase(text, BODY_FONT));
        cell.setHorizontalAlignment(alignment);
        return cell;
    }

    private static PdfPCell totalCell(String text) {
        PdfPCell cell = new PdfPCell(new Phrase(text, LABEL_FONT));
        cell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        return cell;
    }

    private static String money(BigDecimal amount) {
        BigDecimal value = amount == null ? BigDecimal.ZERO : amount;
        return value.toPlainString();
    }

    private static void safeClose(Document document) {
        try {
            if (document.isOpen()) {
                document.close();
            }
        } catch (RuntimeException ignored) {
            // best effort
        }
    }
}
