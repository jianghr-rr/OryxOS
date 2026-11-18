package io.github.oryxlabs.oryxos.memory.backend;

import io.github.oryxlabs.oryxos.core.memory.MemoryEntry;
import io.github.oryxlabs.oryxos.core.error.OryxError;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.time.Instant;
import java.util.*;

/** Length-delimited raw Markdown plus checksummed metadata; content cannot inject record boundaries. */
public final class MemoryTextCodec {
    private static final Map<Integer,String> FOLD=foldTable();
    public static String keyword(String text){
        if(text==null||text.isBlank())throw new OryxError("INVALID_ARGUMENTS","Memory query must not be blank");
        var out=new StringBuilder();Normalizer.normalize(text,Normalizer.Form.NFC).codePoints().forEach(c->out.append(FOLD.getOrDefault(c,new String(Character.toChars(c)))));
        return Normalizer.normalize(out,Normalizer.Form.NFC);
    }
    public static void validateContent(String text){
        if(text==null||text.isBlank()||!StandardCharsets.UTF_8.newEncoder().canEncode(text))throw new OryxError("INVALID_ARGUMENTS","Memory content must be non-blank valid Unicode");
    }
    public static String bounded(String text,int codePoints){int count=text.codePointCount(0,text.length());return count<=codePoints?text:text.substring(0,text.offsetByCodePoints(0,codePoints));}
    public String encode(UUID workspace,List<MemoryEntry> entries){
        var text=new StringBuilder("# OryxOS Memory v1\nworkspace: "+workspace+"\n");
        for(var scope:MemoryEntry.Scope.values()){
            text.append("\n## ").append(scope).append('\n');
            for(var e:entries)if(e.scope()==scope){
                text.append("<!-- oryx-entry ").append(e.id()).append(' ').append(e.createdAt()).append(' ').append(e.sourceExecutionId()).append(' ')
                    .append(Base64.getUrlEncoder().withoutPadding().encodeToString(e.sourceAgentName().getBytes(StandardCharsets.UTF_8))).append(' ')
                    .append(e.rawContent().length()).append(' ').append(hash(e.rawContent())).append(" -->\n");
                text.append(e.rawContent()).append("\n<!-- /oryx-entry -->\n");
            }
        }
        return text.toString();
    }
    public List<MemoryEntry> decode(UUID workspace,String text){
        try {
            String header="# OryxOS Memory v1\nworkspace: "+workspace+"\n";if(!text.startsWith(header))throw invalid();int pos=header.length();
            var entries=new ArrayList<MemoryEntry>();var ids=new HashSet<UUID>();
            for(var scope:MemoryEntry.Scope.values()){
                String section="\n## "+scope+"\n";if(!text.startsWith(section,pos))throw invalid();pos+=section.length();
                while(text.startsWith("<!-- oryx-entry ",pos)){
                    int end=text.indexOf(" -->\n",pos);if(end<0)throw invalid();String[] fields=text.substring(pos+16,end).split(" ");if(fields.length!=6)throw invalid();
                    UUID id=UUID.fromString(fields[0]);if(!ids.add(id))throw invalid();Instant created=Instant.parse(fields[1]);UUID execution=UUID.fromString(fields[2]);
                    String agent=new String(Base64.getUrlDecoder().decode(fields[3]),StandardCharsets.UTF_8);int size=Integer.parseInt(fields[4]);pos=end+5;
                    if(size<1||size>text.length()-pos)throw invalid();String raw=text.substring(pos,pos+size);validateContent(raw);pos+=size;
                    if(!hash(raw).equals(fields[5])||!text.startsWith("\n<!-- /oryx-entry -->\n",pos))throw invalid();pos+="\n<!-- /oryx-entry -->\n".length();
                    entries.add(new MemoryEntry(id,workspace,scope,raw,created,execution,agent,"md",MemoryEntry.WriteStatus.CONFIRMED,List.of()));
                }
            }
            if(pos!=text.length())throw invalid();return List.copyOf(entries);
        }catch(RuntimeException e){throw invalid();}
    }
    private static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private static OryxError invalid(){return new OryxError("MEMORY_CORRUPT","Memory file is invalid; preserve it for inspection");}
    private static Map<Integer,String> foldTable(){
        var map=new HashMap<Integer,String>();
        try(var stream=MemoryTextCodec.class.getResourceAsStream("/unicode/CaseFolding-17.0.0.txt")){
            if(stream==null)throw new IllegalStateException("Missing Unicode folding table");
            for(String line:new String(stream.readAllBytes(),StandardCharsets.UTF_8).split("\n")){
                String data=line.split("#",2)[0].trim();if(data.isEmpty())continue;var f=data.split(";");
                if(!Set.of("C","F").contains(f[1].trim()))continue;var replacement=new StringBuilder();for(String code:f[2].trim().split(" +"))replacement.appendCodePoint(Integer.parseInt(code,16));map.put(Integer.parseInt(f[0].trim(),16),replacement.toString());
            }
        }catch(IOException e){throw new ExceptionInInitializerError(e);}return Map.copyOf(map);
    }
}
