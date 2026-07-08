package io.cratebox.settings;

/** 발행사(운영사) 정보. 정산서 머리글에 표기된다 */
public record OrgProfile(String name, String bizRegNo, String ceoName,
                         String address, String phone, String email) {}
