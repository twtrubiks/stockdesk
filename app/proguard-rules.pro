# OkHttp、jsoup、kotlinx.serialization 都自帶 R8 規則，目前不需要額外的 keep。
# R8 壓過的程式只有 release 版跑得到：加了規則就在這裡寫為什麼，並讓 CI 的 release 冒煙測試真的走過那段程式
