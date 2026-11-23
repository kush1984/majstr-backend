package com.majstr.backend.entity;

/**
 * Where a master works (V219): the 24 oblasts and Kyiv city — the unit Ukrainian prices are talked
 * about in, and the unit «ринкова ціна» will be split by once enough masters have answered. The
 * column's CHECK lists the same values; a new one needs it widened.
 */
public enum Region {
    VINNYTSIA, VOLYN, DNIPRO, DONETSK, ZHYTOMYR, ZAKARPATTIA, ZAPORIZHZHIA, IVANO_FRANKIVSK,
    KYIV_OBLAST, KIROVOHRAD, LUHANSK, LVIV, MYKOLAIV, ODESA, POLTAVA, RIVNE, SUMY, TERNOPIL,
    KHARKIV, KHERSON, KHMELNYTSKYI, CHERKASY, CHERNIVTSI, CHERNIHIV, KYIV_CITY
}
