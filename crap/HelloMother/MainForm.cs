namespace HelloMother;

public class MainForm : Form
{
    public MainForm()
    {
        Text = "Hello Mother";
        ClientSize = new Size(300, 150);
        StartPosition = FormStartPosition.CenterScreen;

        var button = new Button
        {
            Text = "Click me",
            Size = new Size(120, 40),
            Location = new Point((ClientSize.Width - 120) / 2, (ClientSize.Height - 40) / 2),
            Anchor = AnchorStyles.None
        };
        button.Click += (_, _) => MessageBox.Show("hello mother", "Hello");

        Controls.Add(button);
    }
}
