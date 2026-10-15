import React from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { useRouter } from 'expo-router';
import { MnBtn } from '@components/ui/MnBtn/MnBtn';
import { MnCard } from '@components/ui/MnCard/MnCard';
import { MnEyebrow } from '@components/primitives/MnEyebrow/MnEyebrow';
import { FontFamily, FontSize, Space } from '@design/tokens';
import { useTheme } from '@design/useTheme';

/**
 * The Categories & Rules card on Settings (step 212, family B).
 *
 * A component of its own rather than inline in `app/(tabs)/settings.tsx` for
 * the same reason `AiSettingsCard` is (step 214, family D — see that file):
 * `settings.tsx` is appended to by three other families, so this step's
 * change to it stays a single import and a single line; and `app/` sits
 * outside the coverage glob while `src/` does not.
 *
 * The screens themselves live under the Budgets tab's stack
 * (`app/(tabs)/budgets/categories.tsx`, `.../rules.tsx`) rather than under
 * Settings — Settings only offers the way in, so this card is two buttons and
 * nothing else.
 */
export function CategoriesRulesCard() {
  const theme = useTheme();
  const router = useRouter();

  return (
    <>
      <MnEyebrow style={styles.section}>Categories &amp; Rules</MnEyebrow>
      <MnCard style={styles.card}>
        <Text style={[styles.note, { color: theme.ink3 }]}>
          Manage your household&apos;s category tree and the merchant rules that categorise
          transactions automatically.
        </Text>
        <View style={styles.row}>
          <MnBtn
            label="Categories"
            variant="secondary"
            style={styles.grow}
            onPress={() => router.push('/(tabs)/budgets/categories')}
            testID="settings-open-categories"
          />
          <MnBtn
            label="Merchant rules"
            variant="secondary"
            style={styles.grow}
            onPress={() => router.push('/(tabs)/budgets/rules')}
            testID="settings-open-rules"
          />
        </View>
      </MnCard>
    </>
  );
}

const styles = StyleSheet.create({
  section: { marginTop: Space.s4 },
  card: { marginBottom: Space.s4, gap: Space.s3 },
  note: { fontFamily: FontFamily.body, fontSize: FontSize.sm },
  row: { flexDirection: 'row', gap: Space.s2 },
  grow: { flex: 1 },
});
