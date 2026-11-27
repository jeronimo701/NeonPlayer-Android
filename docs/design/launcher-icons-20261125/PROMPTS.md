# Launcher icons · 25 ноября 2026

Инструмент: встроенный image_gen, навык imagegen. Два листа по пять концепций, A1–A5 и B1–B5. Пользователь выбрал B1 по умолчанию и все десять в настройках. Все десять отдельно подготовлены image_gen, без вырезания иконок из листа, сохранены в app/src/main/res/drawable-nodpi/launcher_*.png и подключены через adaptive-icon + launcher aliases. Прежняя оригинальная иконка также доступна. Выбор иконки не меняет тему.

Лист Orange-Controls.png утверждён для темы PH / Orange; реальные кнопки — собственные векторные пути. Deck по умолчанию сохраняет DUO.

## Подготовка отдельных launcher-иконок

Для каждого ID (A1–A5, B1–B5) отдельный вызов встроенного image_gen с соответствующим листом в referenced_image_paths. Точный шаблон:

Edit target: attached concept sheet. Extract and redraw ONLY icon {ID} from the sheet as ONE production Android launcher icon, square image. Keep that specific icon's identity, design, triangle/duck composition, colors and style faithful. No other proposals, NO letters {ID}, NO labels or headings, NO sheet background. Preserve any intentional logo text INSIDE icon {ID} (e.g. Neon Hub in B2). Fill entire square canvas edge-to-edge with the icon's own dark/black or orange background, WITHOUT rounded outer tile corners: Android will apply its own adaptive mask. Main logo centered in central 60% of canvas with generous safe margins for adaptive circular crop. A complete single app icon only. Very crisp professional artwork. No people or sexual content.

## Лист A

Use case: logo-brand. Create ONE high-quality landscape concept comparison sheet for an Android video player named NEON PLAYER. Exactly FIVE app icon proposals, large and equally sized in a clean horizontal row, on a flat dark charcoal canvas. Heading 'NEON · PLAY + DUCK'. Number labels only under icons: 'A1', 'A2', 'A3', 'A4', 'A5'. Every proposal MUST have one large recognizable right-pointing PLAY TRIANGLE and INSIDE that triangle one very recognizable YELLOW RUBBER DUCK with orange beak, no other animals. Rounded-square Android icon tile silhouettes, generous adaptive icon safe area. Five clearly distinct visual treatments: A1 super minimal flat geometric turquoise triangle and simple yellow duck silhouette on black; A2 polished soft 3D glass play triangle with tiny dimensional yellow rubber duck inside; A3 sharp neon cyan outline triangle, yellow neon duck silhouette inside, restrained bloom; A4 playful rounded filled play triangle in ivory and friendly flat yellow duck with eye inside, no text; A5 premium dark metallic play triangle with gold bevel and sculpted bright yellow duck inset inside. Keep triangle and duck readable at launcher icon size, no busy scenes, no UI, no phone mockup, no website logo. This is an icon design exploration sheet, refined professional logo art. Do not include adult imagery.

## Лист B

Use case: logo-brand. Create ONE premium landscape comparison sheet with exactly FIVE Android launcher icon proposals for NEON PLAYER, playful rubber duck video player. Matte charcoal canvas. Heading 'NEON · BLACK + ORANGE'. One clean horizontal row of FIVE equally large rounded-square app icons with safe Android adaptive-icon margins. Labels below: B1, B2, B3, B4, B5 only. Visual inspiration: Pornhub graphic design language, black white and signature warm orange #FF9900, bold heavy sans typography, small orange rounded rectangle contrasting black block. NO adult imagery: subject is ONLY a YELLOW RUBBER DUCK and VIDEO PLAY logo. B1 minimalist orange play triangle with tiny black duck silhouette cutout on black tile; B2 bold compact 'Neon' in white plus 'Hub' black on orange rounded block, small yellow duck above, balanced readable two-line logo; B3 softly rounded orange badge with black play triangle framing flat yellow rubber duck, modern flat illustration; B4 premium glossy orange glass play triangle and bright yellow rubber duck, subtle orange glow on deep black, no turquoise; B5 strict minimal black tile with white play outline and orange duck silhouette centered within, bold simple negative space. Five genuinely distinct treatments, professional crisp logo design, readable at small launcher size, no phone, no player UI, no screenshots, no website or extra branding text. Do not show people, bodies or sexual content.



## Реальная реализация

alpha9-native-specimen.png — native Canvas-превью из Android, не новый AI-макет. Foreground всех десяти adaptive-иконок имеет безопасные поля 14%, чтобы треугольник не срезался круглой маской. Сырые PNG image_gen не редактировались.
