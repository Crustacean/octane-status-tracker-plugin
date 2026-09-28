package io.jenkins.plugins.octanesuitegatebyembiti.utils;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import javax.swing.text.MutableAttributeSet;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.parser.ParserDelegator;

/** Converts ALM Octane rich-text defect descriptions to safe, readable plain text. */
public final class DefectDescriptionFormatter {
  private DefectDescriptionFormatter() {}

  public static String toPlainText(String value) {
    String source = Util.trimToEmpty(value);
    if (source.isEmpty()) {
      return "";
    }
    if (source.indexOf('<') < 0 && source.indexOf('&') < 0) {
      return normalizeLines(source);
    }

    StringBuilder text = new StringBuilder(source.length());
    try {
      new ParserDelegator().parse(new StringReader(source), new PlainTextCallback(text), true);
      return normalizeLines(text.toString());
    } catch (IOException ignored) {
      return normalizeLines(stripMarkupFallback(source));
    }
  }

  private static String stripMarkupFallback(String value) {
    return value
        .replaceAll("(?is)<(?:script|style)[^>]*>.*?</(?:script|style)>", "")
        .replaceAll("(?i)<br\\s*/?>|</?(?:p|div|li|tr|h[1-6]|blockquote|pre)[^>]*>", "\n")
        .replaceAll("(?s)<[^>]*>", "");
  }

  private static String normalizeLines(String value) {
    List<String> lines = new ArrayList<>();
    for (String line : value.replace('\r', '\n').split("\\n+")) {
      String normalized =
          line.replace('\u00a0', ' ').replaceAll("[\\p{Zs}\\t\\x0B\\f]+", " ").trim();
      if (!normalized.isEmpty()) {
        lines.add(normalized);
      }
    }
    return String.join("\n", lines);
  }

  private static final class PlainTextCallback extends HTMLEditorKit.ParserCallback {
    private final StringBuilder text;
    private int ignoredDepth;

    private PlainTextCallback(StringBuilder text) {
      this.text = text;
    }

    @Override
    public void handleStartTag(HTML.Tag tag, MutableAttributeSet attributes, int position) {
      if (isIgnored(tag)) {
        ignoredDepth += 1;
      } else if (ignoredDepth == 0 && isBlock(tag)) {
        appendLineBreak();
      }
    }

    @Override
    public void handleEndTag(HTML.Tag tag, int position) {
      if (isIgnored(tag)) {
        ignoredDepth = Math.max(0, ignoredDepth - 1);
      } else if (ignoredDepth == 0 && isBlock(tag)) {
        appendLineBreak();
      }
    }

    @Override
    public void handleSimpleTag(HTML.Tag tag, MutableAttributeSet attributes, int position) {
      if (ignoredDepth == 0 && (tag == HTML.Tag.BR || isBlock(tag))) {
        appendLineBreak();
      }
    }

    @Override
    public void handleText(char[] data, int position) {
      if (ignoredDepth == 0) {
        text.append(data);
      }
    }

    private void appendLineBreak() {
      if (!text.isEmpty() && text.charAt(text.length() - 1) != '\n') {
        text.append('\n');
      }
    }

    private static boolean isIgnored(HTML.Tag tag) {
      return tag == HTML.Tag.HEAD || tag == HTML.Tag.SCRIPT || tag == HTML.Tag.STYLE;
    }

    private static boolean isBlock(HTML.Tag tag) {
      return tag == HTML.Tag.P
          || tag == HTML.Tag.DIV
          || tag == HTML.Tag.LI
          || tag == HTML.Tag.TR
          || tag == HTML.Tag.H1
          || tag == HTML.Tag.H2
          || tag == HTML.Tag.H3
          || tag == HTML.Tag.H4
          || tag == HTML.Tag.H5
          || tag == HTML.Tag.H6
          || tag == HTML.Tag.BLOCKQUOTE
          || tag == HTML.Tag.PRE;
    }
  }
}
