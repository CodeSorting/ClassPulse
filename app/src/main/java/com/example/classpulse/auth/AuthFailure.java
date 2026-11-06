package com.example.classpulse.auth;

public enum AuthFailure {
    CANCELLED("로그인을 취소했어요. 원할 때 다시 시작할 수 있어요."),
    NO_ACCOUNT("사용할 Google 계정을 찾지 못했어요. 기기에 계정을 추가한 뒤 다시 시도해 주세요."),
    PROVIDER_UNAVAILABLE("Google 로그인 기능을 사용할 수 없어요. Google Play 서비스와 기기 업데이트를 확인해 주세요."),
    NETWORK("연결을 확인한 뒤 다시 로그인해 주세요."),
    CONFIGURATION("Google 로그인 설정을 확인해야 해요. 앱을 업데이트하거나 지원 담당자에게 알려 주세요."),
    REJECTED("계정을 인증하지 못했어요. Google 계정을 다시 선택해 주세요."),
    UNKNOWN("로그인을 완료하지 못했어요. 잠시 후 다시 시도해 주세요.");

    public final String message;

    AuthFailure(String message) {
        this.message = message;
    }
}
