package io.github.xiaofengzhou.qupath.mrxs;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Strict quoted CSV reader; preserves strings, empty fields and embedded newlines. */
final class IfReportCsv {
    static List<List<String>> parse(String text,int maxRows,int maxColumns) throws IOException {
        var rows=new ArrayList<List<String>>();var row=new ArrayList<String>();var field=new StringBuilder();
        boolean quoted=false,afterQuote=false,started=false;
        for(int i=0;i<text.length();i++) {
            char c=text.charAt(i);started=true;
            if(quoted) {
                if(c=='"') {
                    if(i+1<text.length() && text.charAt(i+1)=='"'){field.append('"');i++;}
                    else {quoted=false;afterQuote=true;}
                } else field.append(c);
            } else if(c==',' || c=='\n' || c=='\r') {
                row.add(field.toString());field.setLength(0);afterQuote=false;
                if(row.size()>maxColumns)throw new IOException("CSV column limit exceeded");
                if(c!=',') {
                    rows.add(List.copyOf(row));row.clear();started=false;
                    if(rows.size()>maxRows)throw new IOException("CSV row limit exceeded");
                    if(c=='\r' && i+1<text.length() && text.charAt(i+1)=='\n')i++;
                }
            } else if(c=='"' && field.isEmpty() && !afterQuote)quoted=true;
            else {
                if(afterQuote || c=='"')throw new IOException("Invalid CSV quote boundary");
                field.append(c);
            }
            if(field.length()>32767)throw new IOException("CSV cell exceeds spreadsheet text limit");
        }
        if(quoted)throw new IOException("Unclosed CSV quoted field");
        if(started || !row.isEmpty() || !field.isEmpty()) {
            row.add(field.toString());
            if(row.size()>maxColumns || rows.size()>=maxRows)throw new IOException("CSV size limit exceeded");
            rows.add(List.copyOf(row));
        }
        return List.copyOf(rows);
    }
}
