package clockwork.web;
import java.io.IOException;
import java.util.Base64;
public final class SkinChecks {
    public static void main(String[] args) throws Exception {
        String hash="a".repeat(64);
        if(!PlayerSkins.texture("http://textures.minecraft.net/texture/"+hash).toString().startsWith("https://"))throw new AssertionError("HTTPS upgrade");
        for(String bad : new String[]{"http://127.0.0.1/texture/"+hash,"https://textures.minecraft.net.evil.example/texture/"+hash,"https://textures.minecraft.net/texture/"+hash+"?redirect=x","https://textures.minecraft.net:443/texture/"+hash,"https://textures.minecraft.net/texture/../private"}) {
            try{PlayerSkins.texture(bad);throw new AssertionError("URL accepted: "+bad);}catch(IOException expected){}
        }
        try{PlayerSkins.validatePng(new byte[24]);throw new AssertionError("Bad PNG accepted");}catch(IOException expected){}
        try{new PlayerSkins().fetch("../../private");throw new AssertionError("Bad username accepted");}catch(IOException expected){}
        if(args.length>0){
            var skins=new PlayerSkins(); var value=skins.fetch(args[0]);
            PlayerSkins.validatePng(Base64.getDecoder().decode(value.get("image").getAsString().split(",")[1]));
            if(!skins.fetch(args[0]).equals(value))throw new AssertionError("Cached response changed");
            System.out.println("PASS: live Minecraft skin for "+value.get("username").getAsString()+", slim="+value.get("slim")+", validated PNG and cache");
        }
        System.out.println("PASS: fixed skin host/path, HTTPS, invalid username and PNG rejection");
    }
}
