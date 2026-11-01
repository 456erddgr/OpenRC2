package com.rc2.etx.radio;

/** 一个模型（飞机/机型）的完整配置。 */
public final class Model {
    public String name = "默认模型";
    public ChannelConfig[] ch = Mixer.defaultConfig();

    public Model() {
    }

    public static Model defaults() {
        Model m = new Model();
        m.name = "默认模型 AETR";
        return m;
    }

    public Model copy() {
        Model m = new Model();
        m.name = name;
        ChannelConfig[] c = new ChannelConfig[Mixer.CH_COUNT];
        for (int i = 0; i < Mixer.CH_COUNT; i++) {
            c[i] = ChannelConfig.copyOf(ch[i]);
        }
        m.ch = c;
        return m;
    }
}
